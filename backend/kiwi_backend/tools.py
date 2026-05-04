"""Function-calling tools exposed to Gemini Live.

Tools are how the LLM does anything beyond talking — get the time,
list calendar events, play a song, control the lights one day. Each
tool is:

    1. Declared via a ``FunctionDeclaration`` so Gemini knows it
       exists, what it does, and what arguments it takes.
    2. Backed by a Python ``handler`` callable that runs when Gemini
       decides to call it.

The dispatch runs on the same asyncio loop as the proxy. Handlers
can be async — anything that hits the network (Calendar, Spotify,
YouTube, …) MUST be, otherwise the audio receive loop is blocked
while the API call is in flight. Sync helpers should wrap blocking
calls with ``asyncio.to_thread``.

Some tools also produce a UI scene: a calendar listing wants to push
a calendar view to the tablet, a music play call wants to push a
NowPlaying card. Those tools return ``ToolResult`` instead of a plain
dict; the dispatcher pushes the scene via the ``on_scene`` callback
(supplied by the proxy) and returns the response payload to Gemini.

Adding a new tool is a single ``register`` call. Keep them small and
focused; Gemini composes them itself when a request needs more than
one. Document parameters carefully — the model uses the descriptions
to decide when and how to call.
"""
from __future__ import annotations

import asyncio
import inspect
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from google.genai import types
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from . import google_auth

log = logging.getLogger(__name__)

# Default IANA timezone used when a tool doesn't override it. Backend
# is single-user for now and the user lives in Madrid; pull this out
# into Settings if/when we go multi-user.
DEFAULT_TIMEZONE = "Europe/Madrid"


Handler = Callable[..., Any] | Callable[..., Awaitable[Any]]
SceneSink = Callable[[dict[str, Any]], Awaitable[None]]


@dataclass(frozen=True)
class ToolResult:
    """Tool output that includes both a Gemini response and a scene push.

    Use this when a tool wants the tablet to display something
    (calendar, now-playing, video, …) while Gemini also speaks about
    it. The dispatcher hands ``scene`` to the proxy's scene-push
    callback and returns ``response`` to Gemini.
    """

    response: dict[str, Any]
    scene: dict[str, Any] | None = None


@dataclass(frozen=True)
class _Tool:
    declaration: types.FunctionDeclaration
    handler: Handler


_REGISTRY: dict[str, _Tool] = {}


def register(
    name: str,
    description: str,
    parameters: types.Schema | None,
    handler: Handler,
) -> None:
    """Register a tool. Idempotent per ``name`` (last wins)."""
    if name in _REGISTRY:
        log.info("re-registering tool %r (overwriting previous)", name)
    _REGISTRY[name] = _Tool(
        declaration=types.FunctionDeclaration(
            name=name,
            description=description,
            parameters=parameters,
        ),
        handler=handler,
    )


def gemini_tools() -> list[types.Tool]:
    """Build the ``tools`` list to pass to ``LiveConnectConfig``.

    Gemini wants all function declarations bundled inside a single
    ``Tool`` rather than one ``Tool`` per declaration — this matches
    what their own samples do.
    """
    if not _REGISTRY:
        return []
    return [
        types.Tool(
            function_declarations=[t.declaration for t in _REGISTRY.values()],
        ),
    ]


async def dispatch(
    name: str,
    args: dict[str, Any] | None,
    on_scene: SceneSink | None = None,
) -> dict[str, Any]:
    """Run the named tool and return its JSON-serialisable response.

    If the tool returns a ``ToolResult`` with a non-None ``scene`` and
    the caller supplied an ``on_scene`` callback, the scene is pushed
    *before* the response is returned to Gemini — so the user sees
    the screen update at the same time Kiwi starts speaking about it.

    Errors are caught and returned as ``{"error": "..."}`` so Gemini
    can communicate the problem back to the user instead of the proxy
    crashing mid-turn.
    """
    args = args or {}
    tool = _REGISTRY.get(name)
    if tool is None:
        log.warning("unknown tool requested: %r", name)
        return {"error": f"unknown tool: {name}"}
    try:
        result = tool.handler(**args)
        if inspect.isawaitable(result):
            result = await result
    except Exception as exc:  # noqa: BLE001  — we surface anything to the LLM
        log.exception("tool %r raised", name)
        return {"error": f"{type(exc).__name__}: {exc}"}

    if isinstance(result, ToolResult):
        if result.scene is not None and on_scene is not None:
            try:
                await on_scene(result.scene)
            except Exception:
                # A failed scene push must not break the tool response —
                # Gemini still has to answer the user.
                log.exception("on_scene callback failed for tool %r", name)
        return result.response

    if not isinstance(result, dict):
        # Wrap scalars / lists so the response payload is always a
        # JSON object — Gemini is strict about that.
        result = {"result": result}
    return result


# ---- built-in tools -------------------------------------------------


def _get_current_time(timezone: str = DEFAULT_TIMEZONE) -> dict[str, str]:
    """Return the current local time. Fallback to UTC if the tz is bogus."""
    try:
        tz = ZoneInfo(timezone)
    except ZoneInfoNotFoundError:
        tz = ZoneInfo("UTC")
        timezone = "UTC"
    now = datetime.now(tz)
    return {
        "iso": now.isoformat(timespec="seconds"),
        "timezone": timezone,
        # Spanish locale-ish short form. Datetime locale formatting on
        # Cloud Run is finicky so we hand-format.
        "human": now.strftime("%H:%M del %A %d de %B de %Y"),
    }


register(
    name="get_current_time",
    description=(
        "Devuelve la hora local actual del usuario. Útil cuando el usuario "
        "pregunta la hora, el día de la semana o la fecha. "
        "El backend resuelve la hora real — nunca asumas una respuesta sin "
        "llamar a este tool."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "timezone": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Zona horaria IANA (p.ej. 'Europe/Madrid', 'UTC'). "
                    f"Por defecto {DEFAULT_TIMEZONE!r}."
                ),
            ),
        },
    ),
    handler=_get_current_time,
)


# ---- calendar -------------------------------------------------------

# Lower-cased period values accepted by ``calendar_list_events``. Kept
# small + opinionated so Gemini doesn't have to guess what an arbitrary
# free-form string means.
_CALENDAR_PERIODS = {"today", "tomorrow", "this_week", "next_7_days"}


def _calendar_window(
    period: str, now: datetime
) -> tuple[datetime, datetime] | None:
    """Translate a period name into a (timeMin, timeMax) tuple, or None.

    Returns ``None`` for unrecognised values so the caller can return
    a clean error to Gemini instead of crashing.
    """
    period = period.lower()
    if period == "today":
        return now, now.replace(hour=23, minute=59, second=59, microsecond=0)
    if period == "tomorrow":
        tomorrow = (now + timedelta(days=1)).replace(
            hour=0, minute=0, second=0, microsecond=0,
        )
        return tomorrow, tomorrow.replace(hour=23, minute=59, second=59)
    if period == "this_week":
        # End-of-week = Sunday 23:59:59 in the user's timezone. weekday()
        # gives Monday=0..Sunday=6; we cap at the current day so a Sunday
        # query doesn't silently roll into next Sunday.
        days_until_sunday = max(0, 6 - now.weekday())
        end = (now + timedelta(days=days_until_sunday)).replace(
            hour=23, minute=59, second=59, microsecond=0,
        )
        return now, end
    if period == "next_7_days":
        return now, now + timedelta(days=7)
    return None


def _calendar_event_dto(event: dict[str, Any]) -> dict[str, Any]:
    """Normalise a Google Calendar event into the wire shape we ship.

    We strip everything Gemini and the tablet UI don't need (creator,
    organizer, attendees, …) — keeps tokens / bytes down and avoids
    leaking metadata to the LLM unintentionally.
    """
    start = event.get("start", {}) or {}
    end = event.get("end", {}) or {}
    all_day = "date" in start
    return {
        "title": event.get("summary") or "(sin título)",
        "starts_at": start.get("dateTime") or start.get("date"),
        "ends_at": end.get("dateTime") or end.get("date"),
        "location": event.get("location"),
        "all_day": all_day,
    }


def _list_events_blocking(
    creds, time_min: datetime, time_max: datetime, max_results: int,
) -> list[dict[str, Any]]:
    """Blocking Google Calendar list call. Run me via asyncio.to_thread."""
    service = build("calendar", "v3", credentials=creds, cache_discovery=False)
    response = (
        service.events()
        .list(
            calendarId="primary",
            timeMin=time_min.isoformat(),
            timeMax=time_max.isoformat(),
            maxResults=max_results,
            singleEvents=True,
            orderBy="startTime",
        )
        .execute()
    )
    return [_calendar_event_dto(ev) for ev in response.get("items", [])]


async def _calendar_list_events(
    period: str = "today",
    max_results: int = 20,
) -> ToolResult:
    """Read upcoming events from the user's primary Google Calendar."""
    if period.lower() not in _CALENDAR_PERIODS:
        return ToolResult(
            response={
                "error": (
                    f"unknown period {period!r}. "
                    f"valid: {sorted(_CALENDAR_PERIODS)}"
                ),
            },
        )
    try:
        creds = google_auth.credentials()
    except google_auth.GoogleAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})

    tz = ZoneInfo(DEFAULT_TIMEZONE)
    now = datetime.now(tz)
    window = _calendar_window(period, now)
    assert window is not None  # period validated above
    time_min, time_max = window

    try:
        events = await asyncio.to_thread(
            _list_events_blocking, creds, time_min, time_max, max_results,
        )
    except HttpError as exc:
        log.warning("Calendar API HttpError: %s", exc)
        return ToolResult(
            response={"error": f"calendar api error: {exc.status_code}"},
        )

    return ToolResult(
        response={
            "period": period.lower(),
            "count": len(events),
            "events": events,
        },
        scene={
            "type": "calendar",
            "period": period.lower(),
            "events": events,
        },
    )


register(
    name="calendar_list_events",
    description=(
        "Devuelve los próximos eventos del calendario primario del usuario "
        "para un periodo dado. Llama a este tool cuando el usuario pregunte "
        "por su agenda, qué tiene hoy / mañana / esta semana, o si tiene "
        "algún evento en un día concreto. La respuesta también pinta una "
        "lista de eventos en la pantalla del tablet — no resumas todos los "
        "campos, basta con que comuniques los más relevantes."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "period": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Periodo a consultar. Valores admitidos:\n"
                    "  - 'today' (default): solo hoy.\n"
                    "  - 'tomorrow': solo mañana.\n"
                    "  - 'this_week': desde ahora hasta el domingo 23:59.\n"
                    "  - 'next_7_days': próximos 7 días."
                ),
            ),
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de eventos a devolver. Por defecto 20.",
            ),
        },
    ),
    handler=_calendar_list_events,
)


def registered_names() -> list[str]:
    """Useful for tests + debug logs."""
    return sorted(_REGISTRY)


# Make it explicit that this module's import side-effect is "register
# all built-in tools". The session module imports it lazily so the
# registration happens only once per process.
__all__ = [
    "ToolResult",
    "dispatch",
    "gemini_tools",
    "register",
    "registered_names",
]
