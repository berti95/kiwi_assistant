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

import requests
from google.genai import types
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from . import alarms, google_auth, shopping, spotify_auth, timer, todos, usage, weather

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


class SessionEndRequested(RuntimeError):  # noqa: N818  — semantic name fits better than EndError
    """Una tool ha decidido que la conversación termina.

    Lanzada por p.ej. ``end_conversation``. ``dispatch`` la re-lanza
    intencionadamente (no la convierte en ``{"error": ...}``) para que
    el caller — ``gemini.proxy`` — la capture y cierre el WS tras
    terminar este turno. El control de cierre vive en el caller, no
    en la tool, para que Gemini pueda terminar de hablar antes de que
    el proxy salga del bucle.
    """


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
    crashing mid-turn. ``SessionEndRequested`` se re-lanza
    intencionadamente: indica un fin de sesión solicitado por la
    propia conversación (vía la tool ``end_conversation``) y el
    caller la maneja.
    """
    args = args or {}
    # Defensive: el modelo no debería incluir on_scene como argumento
    # (no está en su schema) pero filtrarlo evita que un input
    # malicioso pueda inyectarlo y suplantar nuestro sink.
    args.pop("on_scene", None)
    tool = _REGISTRY.get(name)
    if tool is None:
        log.warning("unknown tool requested: %r", name)
        return {"error": f"unknown tool: {name}"}
    try:
        # Si el handler declara ``on_scene`` (sólo unas pocas tools
        # lo necesitan para orquestar push+espera+retry dentro de
        # una misma llamada — p.ej. spotify_play despierta Spotify
        # en el tablet antes de reintentar), se lo inyectamos aquí.
        # El resto de handlers no lo declaran y por tanto no lo
        # reciben — siguen siendo funciones puras de sus args.
        call_args = dict(args)
        sig = inspect.signature(tool.handler)
        if on_scene is not None and "on_scene" in sig.parameters:
            call_args["on_scene"] = on_scene
        result = tool.handler(**call_args)
        if inspect.isawaitable(result):
            result = await result
    except SessionEndRequested:
        raise
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


# ---- youtube --------------------------------------------------------

# Maximum number of items we'll ever fetch in one tool call. The
# backend caps the model's `max_results` to this so a runaway
# Gemini call can't blow YouTube's per-request quota up. Real-world
# scenes (video list, playlist contents) become unwieldy beyond
# ~25 cards on a tablet anyway.
_YT_MAX_RESULTS_HARD_CAP = 25


def _youtube_thumbnail(thumbnails: dict | None) -> str | None:
    """Pick the best-quality thumbnail YouTube returned.

    YouTube responses include several sizes (default, medium, high,
    standard, maxres). We prefer high → standard → medium → default
    so the tablet card looks crisp without paying for maxres
    everywhere. Returns the URL or ``None`` when nothing usable
    came back.
    """
    if not thumbnails:
        return None
    for key in ("high", "standard", "medium", "default"):
        url = thumbnails.get(key, {}).get("url")
        if url:
            return url
    return None


def _parse_iso8601_duration(iso: str | None) -> str | None:
    """Convert YouTube's ISO 8601 duration to mm:ss / h:mm:ss.

    YouTube ships durations as ``PT4M13S``, ``PT1H2M5S``, etc. Live
    streams use ``P0D`` and we surface that as ``None`` (the UI
    renders "EN VIVO" elsewhere if needed).
    """
    if not iso or iso == "P0D":
        return None
    import re

    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", iso)
    if not match:
        return None
    h, m, s = (int(g or 0) for g in match.groups())
    if h:
        return f"{h}:{m:02d}:{s:02d}"
    return f"{m}:{s:02d}"


def _youtube_video_dto(
    video_id: str,
    snippet: dict | None,
    duration: str | None = None,
) -> dict[str, Any]:
    """Normalise a YouTube video into the wire shape we ship.

    Works for both ``search.list`` results (snippet only) and
    ``videos.list`` results (snippet + contentDetails). The caller
    enriches with duration when available.
    """
    snippet = snippet or {}
    return {
        "video_id": video_id,
        "title": snippet.get("title") or "(sin título)",
        "channel": snippet.get("channelTitle") or "",
        "thumbnail_url": _youtube_thumbnail(snippet.get("thumbnails")),
        "duration": duration,
    }


def _youtube_playlist_dto(item: dict[str, Any]) -> dict[str, Any]:
    snippet = item.get("snippet") or {}
    content_details = item.get("contentDetails") or {}
    return {
        "playlist_id": item.get("id") or "",
        "title": snippet.get("title") or "(sin título)",
        "item_count": content_details.get("itemCount") or 0,
        "thumbnail_url": _youtube_thumbnail(snippet.get("thumbnails")),
    }


def _youtube_search_blocking(
    creds, query: str, max_results: int,
) -> list[dict[str, Any]]:
    """Search.list (100 quota units) + videos.list (1 unit) for durations."""
    service = build("youtube", "v3", credentials=creds, cache_discovery=False)
    search_response = (
        service.search()
        .list(
            q=query,
            part="snippet",
            type="video",
            maxResults=max_results,
            safeSearch="none",
        )
        .execute()
    )
    items = search_response.get("items", [])
    if not items:
        return []
    video_ids = [it["id"]["videoId"] for it in items if "videoId" in it.get("id", {})]
    durations: dict[str, str | None] = {}
    if video_ids:
        details = (
            service.videos()
            .list(part="contentDetails", id=",".join(video_ids))
            .execute()
        )
        for item in details.get("items", []):
            durations[item["id"]] = _parse_iso8601_duration(
                (item.get("contentDetails") or {}).get("duration"),
            )
    return [
        _youtube_video_dto(
            video_id=it["id"]["videoId"],
            snippet=it.get("snippet"),
            duration=durations.get(it["id"]["videoId"]),
        )
        for it in items
        if "videoId" in it.get("id", {})
    ]


def _youtube_my_playlists_blocking(
    creds, max_results: int,
) -> list[dict[str, Any]]:
    service = build("youtube", "v3", credentials=creds, cache_discovery=False)
    response = (
        service.playlists()
        .list(
            part="snippet,contentDetails",
            mine=True,
            maxResults=max_results,
        )
        .execute()
    )
    return [_youtube_playlist_dto(item) for item in response.get("items", [])]


def _youtube_playlist_items_blocking(
    creds, playlist_id: str, max_results: int,
) -> list[dict[str, Any]]:
    service = build("youtube", "v3", credentials=creds, cache_discovery=False)
    response = (
        service.playlistItems()
        .list(
            part="snippet,contentDetails",
            playlistId=playlist_id,
            maxResults=max_results,
        )
        .execute()
    )
    items = response.get("items", [])
    if not items:
        return []
    # playlistItems doesn't include duration, fetch via videos.list.
    video_ids = [
        (it.get("contentDetails") or {}).get("videoId")
        for it in items
        if (it.get("contentDetails") or {}).get("videoId")
    ]
    durations: dict[str, str | None] = {}
    if video_ids:
        details = (
            service.videos()
            .list(part="contentDetails", id=",".join(video_ids))
            .execute()
        )
        for item in details.get("items", []):
            durations[item["id"]] = _parse_iso8601_duration(
                (item.get("contentDetails") or {}).get("duration"),
            )
    return [
        _youtube_video_dto(
            video_id=(it.get("contentDetails") or {}).get("videoId") or "",
            snippet=it.get("snippet"),
            duration=durations.get(
                (it.get("contentDetails") or {}).get("videoId"),
            ),
        )
        for it in items
        if (it.get("contentDetails") or {}).get("videoId")
    ]


def _resolve_playlist_id(playlists: list[dict[str, Any]], query: str) -> str | None:
    """Fuzzy-match a playlist by name against the user's playlists.

    Lowercase + accent-insensitive contains match. Returns the best
    candidate's ID or None when nothing reasonable matched.
    """
    import unicodedata

    def norm(s: str) -> str:
        s = unicodedata.normalize("NFKD", s)
        s = "".join(c for c in s if not unicodedata.combining(c))
        return s.lower().strip()

    needle = norm(query)
    if not needle:
        return None
    # Exact match first, then prefix match, then substring.
    for p in playlists:
        if norm(p["title"]) == needle:
            return p["playlist_id"]
    for p in playlists:
        if norm(p["title"]).startswith(needle):
            return p["playlist_id"]
    for p in playlists:
        if needle in norm(p["title"]):
            return p["playlist_id"]
    return None


async def _youtube_search(query: str, max_results: int = 10) -> ToolResult:
    """Search YouTube for public videos."""
    if not query or not query.strip():
        return ToolResult(response={"error": "missing query"})
    capped = max(1, min(int(max_results), _YT_MAX_RESULTS_HARD_CAP))
    try:
        creds = google_auth.credentials()
    except google_auth.GoogleAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})
    try:
        videos = await asyncio.to_thread(
            _youtube_search_blocking, creds, query, capped,
        )
    except HttpError as exc:
        log.warning("YouTube search HttpError: %s", exc)
        return ToolResult(
            response={"error": f"youtube api error: {exc.status_code}"},
        )
    return ToolResult(
        response={"query": query, "count": len(videos), "videos": videos},
        scene={"type": "video_list", "title": f"\"{query}\"", "videos": videos},
    )


async def _youtube_my_playlists(max_results: int = 25) -> ToolResult:
    """List the user's own playlists.

    NOTE: The YouTube system playlist "Watch Later" is NOT returned by
    this endpoint — Google removed API access to it in 2016. Encourage
    the user to keep a regular custom playlist as a "ver más tarde"
    substitute (Kiwi can list it via ``youtube_playlist_items``).
    """
    capped = max(1, min(int(max_results), _YT_MAX_RESULTS_HARD_CAP))
    try:
        creds = google_auth.credentials()
    except google_auth.GoogleAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})
    try:
        playlists = await asyncio.to_thread(
            _youtube_my_playlists_blocking, creds, capped,
        )
    except HttpError as exc:
        log.warning("YouTube my_playlists HttpError: %s", exc)
        return ToolResult(
            response={"error": f"youtube api error: {exc.status_code}"},
        )
    return ToolResult(
        response={"count": len(playlists), "playlists": playlists},
        scene={"type": "playlist_list", "playlists": playlists},
    )


async def _youtube_playlist_items(
    playlist_id: str | None = None,
    playlist_name: str | None = None,
    max_results: int = 20,
) -> ToolResult:
    """List videos in a playlist by ID or by name.

    Two intended call patterns:
    - Gemini already knows the ID (e.g. from a previous
      ``youtube_my_playlists`` response) → pass ``playlist_id``.
    - User asks "abre mi playlist Para ver" → Gemini passes
      ``playlist_name="Para ver"`` and the backend looks it up
      against the user's own playlists.
    """
    capped = max(1, min(int(max_results), _YT_MAX_RESULTS_HARD_CAP))
    if not playlist_id and not playlist_name:
        return ToolResult(
            response={"error": "supply either playlist_id or playlist_name"},
        )
    try:
        creds = google_auth.credentials()
    except google_auth.GoogleAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})

    resolved_title: str | None = None
    if not playlist_id:
        try:
            playlists = await asyncio.to_thread(
                _youtube_my_playlists_blocking, creds, _YT_MAX_RESULTS_HARD_CAP,
            )
        except HttpError as exc:
            return ToolResult(
                response={"error": f"youtube api error: {exc.status_code}"},
            )
        playlist_id = _resolve_playlist_id(playlists, playlist_name or "")
        if not playlist_id:
            return ToolResult(
                response={
                    "error": (
                        f"no playlist found matching {playlist_name!r}. "
                        f"available: {[p['title'] for p in playlists]}"
                    ),
                },
            )
        resolved_title = next(
            (p["title"] for p in playlists if p["playlist_id"] == playlist_id),
            None,
        )

    try:
        videos = await asyncio.to_thread(
            _youtube_playlist_items_blocking, creds, playlist_id, capped,
        )
    except HttpError as exc:
        return ToolResult(
            response={"error": f"youtube api error: {exc.status_code}"},
        )

    title = resolved_title or playlist_name or "Playlist"
    return ToolResult(
        response={
            "playlist_id": playlist_id,
            "title": title,
            "count": len(videos),
            "videos": videos,
        },
        scene={"type": "video_list", "title": title, "videos": videos},
    )


async def _youtube_watch_later(max_results: int = 20) -> ToolResult:
    """Read the user's "ver más tarde" playlist.

    YouTube's official Watch Later (system playlist ``WL``) hasn't
    been API-accessible since 2016. The user maintains a regular
    custom playlist (configured in settings) as a stand-in, and
    this tool reads it without making Gemini guess the playlist's
    name.
    """
    from .settings import settings as _settings

    playlist_id = _settings.youtube_watch_later_playlist_id
    if not playlist_id:
        return ToolResult(
            response={
                "error": (
                    "watch_later_playlist_id not configured. Set the "
                    "YOUTUBE_WATCH_LATER_PLAYLIST_ID env var to the ID of "
                    "your 'ver más tarde' playlist."
                ),
            },
        )
    capped = max(1, min(int(max_results), _YT_MAX_RESULTS_HARD_CAP))
    try:
        creds = google_auth.credentials()
    except google_auth.GoogleAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})
    try:
        videos = await asyncio.to_thread(
            _youtube_playlist_items_blocking, creds, playlist_id, capped,
        )
    except HttpError as exc:
        return ToolResult(
            response={"error": f"youtube api error: {exc.status_code}"},
        )
    return ToolResult(
        response={
            "playlist_id": playlist_id,
            "title": "Ver más tarde",
            "count": len(videos),
            "videos": videos,
        },
        scene={
            "type": "video_list",
            "title": "Ver más tarde",
            "videos": videos,
        },
    )


def _youtube_open(url: str | None = None) -> ToolResult:
    """Push the full-YouTube-web browser scene to the tablet.

    Pure scene push — no API call. Used as a fallback when the user
    asks for something we don't have a dedicated tool for (browsing
    the home page, suscripciones, a specific channel page, …).

    The user has to be signed in inside the WebView for personalised
    content. Google blocks WebView-based sign-in, so the first sign-in
    is unreliable and may need to happen via Chrome on the device.
    Once cookies are persisted in the WebView they survive across
    sessions.
    """
    target = (url or "https://m.youtube.com").strip()
    if not target.startswith(("http://", "https://")):
        target = "https://" + target
    return ToolResult(
        response={"opened": target},
        scene={"type": "browse_youtube", "url": target},
    )


def _youtube_play(
    video_id: str,
    title: str = "",
    channel: str = "",
) -> ToolResult:
    """Push the video player scene; the tablet starts playback.

    Pure scene push — no API call. Gemini gives us the video_id from
    a previous tool result (search / playlist items), and we hand
    the IDs straight to the IFrame Player on the tablet.
    """
    if not video_id or not video_id.strip():
        return ToolResult(response={"error": "missing video_id"})
    return ToolResult(
        response={"playing": video_id, "title": title, "channel": channel},
        scene={
            "type": "video_player",
            "video_id": video_id.strip(),
            "title": title,
            "channel": channel,
        },
    )


register(
    name="youtube_search",
    description=(
        "Busca videos públicos en YouTube y muestra los resultados en la "
        "pantalla del tablet (lista de cards con thumbnail). Llama a este "
        "tool cuando el usuario pida buscar / encontrar / ver videos sobre "
        "un tema. La respuesta incluye los video_id; si después dice 'pon "
        "el segundo' o similar, llama a youtube_play con el video_id "
        "correspondiente."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "query": types.Schema(
                type=types.Type.STRING,
                description="Texto de búsqueda en lenguaje natural.",
            ),
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de resultados (1-25, por defecto 10).",
            ),
        },
        required=["query"],
    ),
    handler=_youtube_search,
)

register(
    name="youtube_my_playlists",
    description=(
        "Lista las playlists propias del usuario en YouTube. Útil cuando "
        "pregunte 'qué playlists tengo' o cuando necesites resolver un "
        "nombre de playlist a su ID. NO incluye 'Ver más tarde' — Google "
        "deprecó el acceso a esa playlist por API. Si el usuario pide "
        "'ver más tarde' explícale brevemente y sugiere usar una playlist "
        "personalizada en su lugar (puedes ofrecerte a abrir la que tenga "
        "para ese propósito si la nombra)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de playlists (1-25, por defecto 25).",
            ),
        },
    ),
    handler=_youtube_my_playlists,
)

register(
    name="youtube_playlist_items",
    description=(
        "Lista los videos de una playlist y los muestra en la pantalla. "
        "Acepta playlist_id (cuando ya lo tienes de un tool anterior) o "
        "playlist_name (lo busca contra las playlists del usuario por "
        "coincidencia parcial, sin distinguir mayúsculas ni acentos). "
        "Cuando el usuario diga 'abre mi playlist X' usa playlist_name=X."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "playlist_id": types.Schema(
                type=types.Type.STRING,
                description="ID exacto (p.ej. 'PLp9Klsh4...').",
            ),
            "playlist_name": types.Schema(
                type=types.Type.STRING,
                description="Nombre o trozo del nombre de la playlist.",
            ),
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de videos (1-25, por defecto 20).",
            ),
        },
    ),
    handler=_youtube_playlist_items,
)

register(
    name="youtube_watch_later",
    description=(
        "Devuelve y muestra los videos guardados por el usuario para ver "
        "más tarde. Llama a este tool SIEMPRE que el usuario diga 'ver "
        "más tarde', 'pendientes', 'videos guardados', 'lo que tengo "
        "para ver', o cualquier variante similar. Es un atajo a la "
        "playlist personalizada que el usuario mantiene como sustituto "
        "del 'Watch Later' nativo (que YouTube no expone por API). "
        "Después de mostrar la lista, si el usuario dice 'pon el N' usa "
        "youtube_play con el video_id correspondiente."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de videos (1-25, por defecto 20).",
            ),
        },
    ),
    handler=_youtube_watch_later,
)

register(
    name="youtube_open",
    description=(
        "Abre YouTube en pantalla completa para que el usuario navegue "
        "manualmente con el dedo (suscripciones, recomendaciones, página "
        "principal, un canal concreto, etc.). Llama a este tool SOLO "
        "cuando no haya una tool específica que cubra la petición. Si el "
        "usuario pide ver una playlist concreta usa "
        "youtube_playlist_items; si pide 'ver más tarde' / 'pendientes' "
        "usa youtube_watch_later; si pide buscar usa youtube_search; si "
        "pide reproducir un video usa youtube_play. Acepta una URL "
        "específica de youtube.com si la conversación lo justifica; en "
        "otro caso abre la home móvil por defecto."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "url": types.Schema(
                type=types.Type.STRING,
                description=(
                    "URL específica de youtube.com a abrir, opcional. "
                    "Por defecto la home móvil https://m.youtube.com."
                ),
            ),
        },
    ),
    handler=_youtube_open,
)

register(
    name="youtube_play",
    description=(
        "Reproduce un video de YouTube en la pantalla del tablet. Llama "
        "a este tool con el video_id que has obtenido de una llamada "
        "previa a youtube_search o youtube_playlist_items. Title y "
        "channel son opcionales y se muestran sobre el reproductor."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "video_id": types.Schema(
                type=types.Type.STRING,
                description="ID de YouTube del video (11 caracteres).",
            ),
            "title": types.Schema(
                type=types.Type.STRING,
                description="Título del video, opcional.",
            ),
            "channel": types.Schema(
                type=types.Type.STRING,
                description="Nombre del canal, opcional.",
            ),
        },
        required=["video_id"],
    ),
    handler=_youtube_play,
)


# ---- spotify -------------------------------------------------------

SPOTIFY_API_BASE = "https://api.spotify.com/v1"


def _spotify_get(path: str, params: dict | None = None) -> dict:
    """GET helper that wires the access token + relative path.

    Returns the parsed JSON. Empty 204 responses become {}.
    Raises ``requests.HTTPError`` on non-2xx; the caller usually
    catches it and returns an error dict to Gemini.
    """
    holder = spotify_auth.token_holder()
    response = requests.get(
        f"{SPOTIFY_API_BASE}{path}",
        headers={"Authorization": f"Bearer {holder.access_token()}"},
        params=params or {},
        timeout=15,
    )
    if response.status_code == 204:
        return {}
    response.raise_for_status()
    return response.json()


def _spotify_send(method: str, path: str, json_body: dict | None = None) -> dict:
    """PUT/POST/DELETE helper. Returns {} on 204 (Spotify's "ok empty")."""
    holder = spotify_auth.token_holder()
    response = requests.request(
        method,
        f"{SPOTIFY_API_BASE}{path}",
        headers={"Authorization": f"Bearer {holder.access_token()}"},
        json=json_body,
        timeout=15,
    )
    if response.status_code == 204:
        return {}
    if not response.ok:
        # Spotify-specific 404: "no active device". Surface a friendly
        # message that Gemini can relay verbatim instead of "404".
        if response.status_code == 404 and "device" in response.text.lower():
            raise SpotifyNoDeviceError()
        response.raise_for_status()
    # Defensive parse: algunos endpoints de playback control devuelven
    # 200 OK con cuerpo vacío o no-JSON (visto en producción con
    # /me/player/next: status 200, body vacío → JSONDecodeError). Si
    # ya pasamos response.ok, la acción se ejecutó; un cuerpo no
    # parseable equivale a un response.json() == {} para nuestros
    # callers.
    body = response.text.strip() if response.text else ""
    if not body:
        return {}
    try:
        return response.json()
    except (ValueError, requests.exceptions.JSONDecodeError):
        log.info(
            "spotify %s %s: 200 body no es JSON (%r); tratado como vacío",
            method, path, body[:200],
        )
        return {}


class SpotifyNoDeviceError(RuntimeError):
    """Spotify has no active device to send the command to."""


def _spotify_image_url(images: list[dict] | None) -> str | None:
    """Pick the largest-but-reasonable cover image."""
    if not images:
        return None
    # Spotify orders images largest-first usually, but be defensive
    # and pick by width.
    best = max(images, key=lambda i: i.get("width") or 0)
    return best.get("url")


def _spotify_track_dto(track: dict | None) -> dict[str, Any] | None:
    if not track:
        return None
    artists = ", ".join(a.get("name", "") for a in track.get("artists") or [])
    album = track.get("album") or {}
    return {
        "uri": track.get("uri"),
        "title": track.get("name") or "",
        "artist": artists,
        "album": album.get("name") or "",
        "album_art_url": _spotify_image_url(album.get("images")),
        "duration_ms": track.get("duration_ms") or 0,
    }


def _now_playing_scene(payload: dict | None, *, is_playing: bool) -> dict | None:
    """Build the wire-format NowPlaying scene from /me/player payload."""
    track = _spotify_track_dto(payload.get("item") if payload else None)
    if not track:
        return None
    return {
        "type": "now_playing",
        "title": track["title"],
        "artist": track["artist"],
        "album": track["album"],
        "album_art_url": track["album_art_url"],
        "is_playing": is_playing,
        "duration_ms": track["duration_ms"],
        "progress_ms": (payload or {}).get("progress_ms") or 0,
    }


# --- read state -----------------------------------------------------


def _spotify_currently_playing_blocking() -> tuple[dict, dict | None]:
    """Returns (response_for_gemini, scene_payload)."""
    try:
        payload = _spotify_get("/me/player")
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}, None
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}, None
    if not payload:
        return {"playing": False, "track": None}, None
    track = _spotify_track_dto(payload.get("item"))
    is_playing = bool(payload.get("is_playing"))
    response = {
        "playing": is_playing,
        "track": track,
        "device": (payload.get("device") or {}).get("name"),
    }
    scene = _now_playing_scene(payload, is_playing=is_playing)
    return response, scene


async def _spotify_currently_playing() -> ToolResult:
    response, scene = await asyncio.to_thread(_spotify_currently_playing_blocking)
    return ToolResult(response=response, scene=scene)


# --- search ---------------------------------------------------------


_VALID_SEARCH_TYPES = {"track", "album", "artist", "playlist"}


def _spotify_search_blocking(
    query: str, kind: str, limit: int,
) -> dict[str, Any]:
    # market=from_token requiere el scope `user-read-private`, que el
    # bundle OAuth actual no tiene → Spotify devuelve 403. Como Kiwi
    # vive en España, fijamos market=ES; resultados se filtran a lo
    # reproducible aquí sin pedir un scope nuevo. Si en algún momento
    # hace falta multi-región, mover a settings.
    payload = _spotify_get(
        "/search",
        params={
            "q": query,
            "type": kind,
            "limit": min(max(limit, 1), 20),
            "market": "ES",
        },
    )
    items: list[dict[str, Any]] = []
    if kind == "track":
        for t in (payload.get("tracks") or {}).get("items") or []:
            items.append(_spotify_track_dto(t) or {})
    elif kind == "album":
        for a in (payload.get("albums") or {}).get("items") or []:
            items.append({
                "uri": a.get("uri"),
                "title": a.get("name") or "",
                "artist": ", ".join(
                    x.get("name", "") for x in a.get("artists") or []
                ),
                "album_art_url": _spotify_image_url(a.get("images")),
            })
    elif kind == "artist":
        for a in (payload.get("artists") or {}).get("items") or []:
            items.append({
                "uri": a.get("uri"),
                "title": a.get("name") or "",
                "album_art_url": _spotify_image_url(a.get("images")),
            })
    elif kind == "playlist":
        for p in (payload.get("playlists") or {}).get("items") or []:
            if not p:
                continue
            items.append({
                "uri": p.get("uri"),
                "title": p.get("name") or "",
                "owner": (p.get("owner") or {}).get("display_name") or "",
                "album_art_url": _spotify_image_url(p.get("images")),
            })
    return {"kind": kind, "query": query, "count": len(items), "items": items}


async def _spotify_search(
    query: str,
    kind: str = "track",
    max_results: int = 10,
) -> ToolResult:
    if not query or not query.strip():
        return ToolResult(response={"error": "missing query"})
    kind = kind.lower()
    if kind not in _VALID_SEARCH_TYPES:
        return ToolResult(response={
            "error": (
                f"unknown kind {kind!r}. valid: {sorted(_VALID_SEARCH_TYPES)}"
            ),
        })
    try:
        result = await asyncio.to_thread(
            _spotify_search_blocking, query, kind, max_results,
        )
    except requests.HTTPError as exc:
        return ToolResult(
            response={"error": f"spotify api error: {exc.response.status_code}"},
        )
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})
    return ToolResult(response=result)


# --- playback control ----------------------------------------------


def _try_resolve_first_track(query: str) -> dict | None:
    """Look up the first track URI matching ``query``."""
    result = _spotify_search_blocking(query, "track", 1)
    items = result.get("items") or []
    return items[0] if items else None


def _spotify_play_blocking(
    uri: str | None, query: str | None,
) -> tuple[dict, dict | None]:
    """Start playback. Returns (response, scene_or_none)."""
    target_uri: str | None = uri
    track_meta: dict | None = None
    if not target_uri and query:
        track_meta = _try_resolve_first_track(query)
        if not track_meta:
            return {"error": f"no spotify track found for {query!r}"}, None
        target_uri = track_meta.get("uri")
    if not target_uri:
        return {"error": "supply either uri or query"}, None

    try:
        body: dict[str, Any]
        if target_uri.startswith("spotify:track:"):
            body = {"uris": [target_uri]}
        else:
            # Albums, artists, playlists go via context_uri.
            body = {"context_uri": target_uri}
        _spotify_send("PUT", "/me/player/play", json_body=body)
    except SpotifyNoDeviceError:
        return {
            "error": (
                "no active spotify device. Abre Spotify en el móvil o "
                "PC para que aparezca como dispositivo activo."
            ),
        }, None
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}, None
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}, None

    # Build a NowPlaying scene from what we know plus a best-effort
    # /me/player snapshot (the start can lag a beat so the snapshot
    # may still be the previous track — we fall back to the search
    # metadata in that case).
    scene: dict | None = None
    try:
        snapshot = _spotify_get("/me/player")
        scene = _now_playing_scene(snapshot, is_playing=True)
    except Exception:  # noqa: BLE001
        pass
    if scene is None and track_meta:
        scene = {
            "type": "now_playing",
            "title": track_meta.get("title") or "",
            "artist": track_meta.get("artist") or "",
            "album": track_meta.get("album") or "",
            "album_art_url": track_meta.get("album_art_url"),
            "is_playing": True,
            "duration_ms": track_meta.get("duration_ms") or 0,
            "progress_ms": 0,
        }
    return {"started": target_uri, "track": track_meta}, scene


_SPOTIFY_PACKAGE = "com.spotify.music"
_SPOTIFY_WAKE_DELAY_S = 4.0


def _looks_like_no_active_device(response: dict[str, Any]) -> bool:
    err = str(response.get("error") or "").lower()
    return "no active" in err and "device" in err


async def _spotify_play(
    uri: str | None = None,
    query: str | None = None,
    *,
    on_scene: SceneSink | None = None,
) -> ToolResult:
    """Reproduce algo en Spotify; si no hay device activo, "despierta"
    la app de Spotify en el tablet y reintenta una vez.

    El flow auto-wake imita lo que hace Waze: si no hay nadie
    escuchando, le pedimos al tablet que lance la app de Spotify
    (Intent ``launchIntentForPackage``), esperamos a que Spotify se
    registre como Connect device (~3-4 s), y volvemos a reproducir.
    El cliente además vuelve a Kiwi en foreground tras un breve
    delay para que la conversación siga viva.

    Una sola llamada a la tool orquesta todo, así que Kiwi sólo
    habla una vez (con el resultado final) en lugar de pedir al
    usuario que abra Spotify a mano.
    """
    response, scene = await asyncio.to_thread(_spotify_play_blocking, uri, query)

    if _looks_like_no_active_device(response) and on_scene is not None:
        log.info("spotify_play: no active device → waking Spotify on tablet")
        try:
            await on_scene({
                "type": "device_command",
                "command": "open_app_then_return",
                "package": _SPOTIFY_PACKAGE,
            })
        except Exception:  # noqa: BLE001
            log.exception("device_command push failed")
        await asyncio.sleep(_SPOTIFY_WAKE_DELAY_S)
        log.info("spotify_play: retrying after wake")
        response, scene = await asyncio.to_thread(_spotify_play_blocking, uri, query)

    return ToolResult(response=response, scene=scene)


def _spotify_simple_command(method: str, path: str) -> dict:
    try:
        _spotify_send(method, path)
        return {"ok": True}
    except SpotifyNoDeviceError:
        return {
            "error": (
                "no active spotify device. Abre Spotify en el móvil o "
                "PC primero."
            ),
        }
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


async def _spotify_pause() -> dict:
    return await asyncio.to_thread(
        _spotify_simple_command, "PUT", "/me/player/pause",
    )


async def _spotify_resume() -> dict:
    return await asyncio.to_thread(
        _spotify_simple_command, "PUT", "/me/player/play",
    )


async def _spotify_next() -> dict:
    return await asyncio.to_thread(
        _spotify_simple_command, "POST", "/me/player/next",
    )


async def _spotify_previous() -> dict:
    return await asyncio.to_thread(
        _spotify_simple_command, "POST", "/me/player/previous",
    )


# --- registrations --------------------------------------------------


register(
    name="spotify_currently_playing",
    description=(
        "Devuelve qué se está reproduciendo ahora en Spotify y muestra "
        "una pantalla NowPlaying con la carátula. Útil cuando el "
        "usuario pregunte 'qué suena', 'qué estoy escuchando'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_currently_playing,
)

register(
    name="spotify_search",
    description=(
        "Busca en Spotify. Por defecto busca tracks; pasa kind="
        "'album' / 'artist' / 'playlist' si el usuario lo pide "
        "explícitamente. Tras la respuesta el usuario puede decir "
        "'pon ese' / 'el segundo' y entonces llamas a spotify_play "
        "con el uri correspondiente."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "query": types.Schema(
                type=types.Type.STRING,
                description="Texto de búsqueda libre.",
            ),
            "kind": types.Schema(
                type=types.Type.STRING,
                description=(
                    "'track' (default), 'album', 'artist' o 'playlist'."
                ),
            ),
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de resultados (1-20, por defecto 10).",
            ),
        },
        required=["query"],
    ),
    handler=_spotify_search,
)

register(
    name="spotify_play",
    description=(
        "Empieza a reproducir algo en Spotify. Acepta 'uri' (Spotify URI: "
        "spotify:track:..., spotify:album:..., etc.) cuando ya lo tienes "
        "de un spotify_search anterior, o 'query' (texto libre) cuando "
        "el usuario describe lo que quiere oír y no hemos buscado todavía. "
        "Si Spotify devuelve 'no active device', dile al usuario que abra "
        "Spotify en su móvil o PC para activarlo (de momento — pronto el "
        "tablet será también un dispositivo Spotify)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "uri": types.Schema(
                type=types.Type.STRING,
                description="URI de Spotify (track / album / artist / playlist).",
            ),
            "query": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Texto libre. Cuando lo uses se hace una búsqueda "
                    "implícita y se reproduce el primer resultado."
                ),
            ),
        },
    ),
    handler=_spotify_play,
)

register(
    name="spotify_pause",
    description="Pausa la reproducción de Spotify.",
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_pause,
)

register(
    name="spotify_resume",
    description="Reanuda la reproducción de Spotify pausada.",
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_resume,
)

register(
    name="spotify_next",
    description="Salta a la siguiente canción en Spotify.",
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_next,
)

register(
    name="spotify_previous",
    description=(
        "Vuelve a la canción anterior en Spotify. Si llevas pocos "
        "segundos en la canción actual Spotify suele saltar a la "
        "anterior; si llevas más, reinicia la actual."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_previous,
)


# ---- spotify connect: transfer between devices ---------------------


def _spotify_devices_blocking() -> list[dict]:
    """Return the list of available Spotify Connect devices."""
    payload = _spotify_get("/me/player/devices")
    return payload.get("devices") or []


def _spotify_transfer_blocking(device_id: str) -> tuple[dict, dict | None]:
    """Move playback to ``device_id``. Returns (response, scene)."""
    try:
        # play=True keeps the music going; if nothing was playing
        # Spotify will pick up where it left off (or stay paused on
        # devices with no recent state).
        _spotify_send(
            "PUT", "/me/player",
            json_body={"device_ids": [device_id], "play": True},
        )
    except SpotifyNoDeviceError:
        return {"error": (
            "Spotify no encuentra el dispositivo. Abre la app de "
            "Spotify en él para activarlo."
        )}, None
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}, None
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}, None

    # Best-effort: read /me/player so we can show a now-playing card.
    scene: dict | None = None
    try:
        snapshot = _spotify_get("/me/player")
        scene = _now_playing_scene(snapshot, is_playing=bool(snapshot.get("is_playing")))
    except Exception:  # noqa: BLE001
        pass
    return {"transferred_device_id": device_id}, scene


async def _spotify_play_here() -> ToolResult:
    """Move Spotify Connect playback to the configured tablet device."""
    from .settings import settings as _settings

    pattern = (_settings.kiwi_spotify_tablet_name or "tablet").lower()
    try:
        devices = await asyncio.to_thread(_spotify_devices_blocking)
    except requests.HTTPError as exc:
        return ToolResult(
            response={"error": f"spotify api error: {exc.response.status_code}"},
        )
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})

    target = next(
        (d for d in devices if pattern in (d.get("name") or "").lower()),
        None,
    )
    if target is None:
        names = [d.get("name") or "?" for d in devices]
        return ToolResult(response={
            "error": (
                f"no se encontró el tablet (buscando {pattern!r}) en los "
                f"dispositivos de Spotify Connect: {names}. Asegúrate de "
                "tener la app de Spotify instalada y abierta al menos "
                "una vez en el tablet."
            ),
        })

    response, scene = await asyncio.to_thread(
        _spotify_transfer_blocking, target["id"],
    )
    response = {**response, "device_name": target.get("name")}
    return ToolResult(response=response, scene=scene)


async def _spotify_transfer_to(target: str) -> ToolResult:
    """Move Spotify playback to a named device (fuzzy match)."""
    if not target or not target.strip():
        return ToolResult(response={"error": "target requerido"})
    try:
        devices = await asyncio.to_thread(_spotify_devices_blocking)
    except requests.HTTPError as exc:
        return ToolResult(
            response={"error": f"spotify api error: {exc.response.status_code}"},
        )
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return ToolResult(response={"error": str(exc)})

    from . import state_store

    matched = state_store.fuzzy_match_one(
        list(devices), target, key=lambda d: d.get("name") or "",
    )
    if matched is None:
        names = [d.get("name") or "?" for d in devices]
        return ToolResult(response={
            "error": f"ningún dispositivo coincide con {target!r}. Disponibles: {names}",
        })

    response, scene = await asyncio.to_thread(
        _spotify_transfer_blocking, matched["id"],
    )
    response = {**response, "device_name": matched.get("name")}
    return ToolResult(response=response, scene=scene)


register(
    name="spotify_play_here",
    description=(
        "Transfiere la reproducción de Spotify al propio tablet. Llama "
        "a este tool cuando el usuario diga 'pon Spotify aquí', "
        "'reproduce aquí', 'cambia el sonido al tablet', 'tráelo a "
        "este altavoz', etc. Requiere que la app de Spotify esté "
        "instalada y abierta al menos una vez en el tablet (para que "
        "aparezca como dispositivo Connect). Si no aparece, devuelve "
        "un error explicando cómo activarlo."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_play_here,
)


register(
    name="spotify_transfer_to",
    description=(
        "Transfiere la reproducción de Spotify a un dispositivo "
        "concreto por nombre (fuzzy match, sin acentos ni mayúsculas). "
        "Llama cuando el usuario diga 'pon Spotify en el móvil', "
        "'cambia al ordenador', 'pásalo al salón', etc. Para 'aquí' / "
        "'en el tablet' usa spotify_play_here."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "target": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del nombre del dispositivo destino, p.ej. "
                    "'móvil', 'pixel', 'salón', 'pc'."
                ),
            ),
        },
        required=["target"],
    ),
    handler=_spotify_transfer_to,
)


# ---- todos ---------------------------------------------------------


def _todo_list_scene(items: list[todos.TodoItem]) -> dict[str, Any]:
    """Wire-format scene push for the tablet's TodoList view."""
    return {"type": "todo_list", "items": todos.to_wire(items)}


async def _todo_add(text: str) -> ToolResult:
    """Append a TODO and push the updated list to the tablet."""
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(todos.add, text)
        items = await asyncio.to_thread(todos.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_add failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "added": {"id": item.id, "text": item.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_list() -> ToolResult:
    """Read the user's TODOs and push the list to the tablet."""
    try:
        items = await asyncio.to_thread(todos.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_list failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    pending = [it for it in items if it.completed_ms is None]
    return ToolResult(
        response={
            "count": len(items),
            "pending": len(pending),
            "items": todos.to_wire(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_complete(match: str) -> ToolResult:
    """Mark a TODO as done. Looks up by id or by fuzzy text match."""
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        completed = await asyncio.to_thread(todos.complete, match)
        items = await asyncio.to_thread(todos.list_all)
    except todos.TodoNotFoundError:
        # Re-read so the LLM can tell the user what's actually pending.
        try:
            items = await asyncio.to_thread(todos.list_all)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no todo matched {match!r}. pending: "
                    f"{[it.text for it in items if it.completed_ms is None]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_complete failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "completed": {"id": completed.id, "text": completed.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_remove(match: str) -> ToolResult:
    """Delete a TODO. Looks up by id or by fuzzy text match."""
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(todos.remove, match)
        items = await asyncio.to_thread(todos.list_all)
    except todos.TodoNotFoundError:
        try:
            items = await asyncio.to_thread(todos.list_all)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no todo matched {match!r}. existing: "
                    f"{[it.text for it in items]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_remove failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "removed": {"id": removed.id, "text": removed.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


register(
    name="todo_add",
    description=(
        "Apunta una tarea o recordatorio en la lista de pendientes del "
        "usuario. Llama a este tool cuando el usuario diga 'apúntame', "
        "'recuérdame', 'añade a la lista', 'tengo que', 'no se me olvide' "
        "o variantes similares. La pantalla del tablet se actualiza con "
        "la lista completa; tu respuesta hablada confirma brevemente lo "
        "que apuntaste."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "text": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Texto LITERAL de la tarea, lo más fiel posible a lo "
                    "que dijo el usuario, sin resumir ni reformular. Si "
                    "añadió motivo / contexto / plazo / descripción larga, "
                    "inclúyelo entero — el desarrollador lee la lista para "
                    "implementarla. Captura completa > captura corta."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_todo_add,
)

register(
    name="todo_list",
    description=(
        "Devuelve y muestra la lista completa de pendientes del usuario. "
        "Llama a este tool cuando pregunte 'qué tengo pendiente', 'qué "
        "tengo que hacer', 'cuáles son mis tareas', 'enséñame la lista', "
        "etc. La pantalla muestra el detalle; tu voz resume cuántas hay "
        "y, si son pocas, las menciona."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_todo_list,
)

register(
    name="todo_complete",
    description=(
        "Marca una tarea pendiente como completada. Llama a este tool "
        "cuando el usuario diga 'ya hice X', 'tacha X', 'márcame X "
        "como hecho' o similar. Pasa en 'match' un trozo del texto de "
        "la tarea (o su id si lo conoces de un tool anterior); el "
        "backend hace coincidencia parcial sin distinguir mayúsculas "
        "ni acentos. Si no encuentra una coincidencia única, devuelve "
        "un error con la lista actual para que pidas al usuario que "
        "concrete."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del texto de la tarea (o su id). P.ej. "
                    "'tomates' para 'comprar tomates'."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_todo_complete,
)

async def _get_weather() -> dict[str, Any]:
    """Return current weather for the configured location."""
    snap = await asyncio.to_thread(weather.current)
    if snap is None:
        return {"error": "weather service unavailable"}
    return weather.to_wire(snap)


# ---- timer ---------------------------------------------------------


def _timer_scene(active: timer.ActiveTimer) -> dict[str, Any]:
    return {"type": "timer", **timer.to_wire(active)}


def _timer_start(
    duration_seconds: int = 0,
    minutes: int = 0,
    hours: int = 0,
    label: str = "",
) -> ToolResult:
    """Start a kitchen-style timer. Replaces any active one."""
    total = int(duration_seconds) + int(minutes) * 60 + int(hours) * 3600
    if total <= 0:
        return ToolResult(
            response={
                "error": (
                    "duración inválida. Pasa duration_seconds, minutes "
                    "u hours con un valor positivo."
                ),
            },
        )
    try:
        active = timer.start(total, label=label)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    return ToolResult(
        response={
            "started": True,
            "remaining_seconds": total,
            "label": active.label,
        },
        scene=_timer_scene(active),
    )


def _timer_cancel() -> ToolResult:
    cancelled = timer.cancel()
    if cancelled is None:
        return ToolResult(response={"cancelled": False, "reason": "no active timer"})
    # Push a "no timer" scene so the tablet leaves the TimerScene.
    return ToolResult(
        response={"cancelled": True, "label": cancelled.label},
        scene={"type": "timer", "ends_at_ms": 0, "label": "", "remaining_seconds": 0},
    )


def _timer_status() -> ToolResult:
    active = timer.current()
    if active is None:
        return ToolResult(response={"active": False})
    return ToolResult(
        response={"active": True, **timer.to_wire(active)},
        scene=_timer_scene(active),
    )


register(
    name="timer_start",
    description=(
        "Inicia un temporizador en el tablet. Llama a este tool cuando "
        "el usuario diga 'ponme un temporizador de X', 'avísame en X', "
        "'cuenta atrás de X', etc. Acepta duración en segundos, minutos "
        "y/o horas (combínalos si el usuario dice '1 hora y media'). "
        "Si añade un nombre ('para la pasta'), pásalo en label. "
        "Sustituye cualquier temporizador previo (un solo timer activo "
        "a la vez). El tablet pinta la cuenta atrás y suena al acabar."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "duration_seconds": types.Schema(
                type=types.Type.INTEGER,
                description="Segundos. Combinable con minutes/hours.",
            ),
            "minutes": types.Schema(
                type=types.Type.INTEGER,
                description="Minutos. Combinable con seconds/hours.",
            ),
            "hours": types.Schema(
                type=types.Type.INTEGER,
                description="Horas. Combinable con seconds/minutes.",
            ),
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta breve, opcional ('pasta', 'horno', 'lavadora'). "
                    "Se muestra debajo de la cuenta atrás."
                ),
            ),
        },
    ),
    handler=_timer_start,
)

register(
    name="timer_cancel",
    description=(
        "Cancela el temporizador activo. Llama cuando el usuario diga "
        "'cancela el temporizador', 'quita la cuenta atrás', 'olvida el "
        "timer', etc. Si no hay ninguno activo, simplemente confírmalo."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_timer_cancel,
)

register(
    name="timer_status",
    description=(
        "Devuelve cuánto le queda al temporizador activo. Llama cuando "
        "el usuario pregunte 'cuánto queda', 'cuánto falta', 'cuánto "
        "tiempo tengo aún', etc. Si no hay temporizador, dilo en lugar "
        "de inventar."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_timer_status,
)


# ---- alarms / despertador ------------------------------------------


def _alarm_list_scene(items: list[alarms.Alarm]) -> dict[str, Any]:
    return {"type": "alarm_list", "items": alarms.to_wire(items)}


def _parse_when(when_iso: str) -> int:
    """Parse an ISO 8601 datetime in the user's TZ to epoch-ms.

    Accepts both naive ('2026-05-08T07:00:00') and aware
    ('2026-05-08T07:00:00+02:00') strings. Naive values are
    interpreted as DEFAULT_TIMEZONE (Madrid) — the common case for
    Gemini ("a las 7" → it omits the offset).
    """
    try:
        dt = datetime.fromisoformat(when_iso)
    except ValueError as exc:
        raise ValueError(f"when no es ISO 8601: {when_iso!r}") from exc
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=ZoneInfo(DEFAULT_TIMEZONE))
    return int(dt.timestamp() * 1000)


async def _alarm_set(when: str = "", label: str = "") -> ToolResult:
    """Schedule a one-shot alarm at the given ISO 8601 datetime."""
    if not when:
        return ToolResult(
            response={"error": "when requerido (ISO 8601, ej '2026-05-08T07:00:00')"},
        )
    try:
        fires_at_ms = _parse_when(when)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    try:
        alarm = await asyncio.to_thread(alarms.set_alarm, fires_at_ms, label)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={
            "scheduled": {
                "id": alarm.id,
                "fires_at_ms": alarm.fires_at_ms,
                "label": alarm.label,
            },
            "count": len(items),
        },
        scene=_alarm_list_scene(items),
    )


async def _alarm_cancel(match: str = "") -> ToolResult:
    if not match.strip():
        return ToolResult(response={"error": "match requerido (id o etiqueta)"})
    try:
        cancelled = await asyncio.to_thread(alarms.cancel, match)
    except alarms.AlarmNotFoundError:
        items = await asyncio.to_thread(alarms.list_active)
        return ToolResult(response={
            "error": (
                f"no se encontró despertador {match!r}. Activos: "
                f"{[a.label or a.id for a in items]}"
            ),
        })
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={
            "cancelled": {"id": cancelled.id, "label": cancelled.label},
            "count": len(items),
        },
        scene=_alarm_list_scene(items),
    )


async def _alarm_list() -> ToolResult:
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={"count": len(items), "items": alarms.to_wire(items)},
        scene=_alarm_list_scene(items),
    )


register(
    name="alarm_set",
    description=(
        "Programa un despertador / alarma para una hora concreta. "
        "Llama cuando el usuario diga 'despiértame mañana a las 7', "
        "'avísame el viernes a las 8 y media', 'ponme una alarma a las "
        "21:30', etc. Convierte el momento a ISO 8601 en hora local "
        "(zona Europe/Madrid si el usuario no dice otra cosa). Acepta "
        "una etiqueta opcional para distinguirlo de otros despertadores. "
        "Pueden coexistir varios despertadores activos a la vez."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "when": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Momento en formato ISO 8601 ('2026-05-08T07:00:00' "
                    "o con offset '2026-05-08T07:00:00+02:00'). Sin "
                    "offset se asume Europe/Madrid. Debe ser futuro."
                ),
            ),
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta breve opcional ('trabajo', 'gimnasio', "
                    "'tren'). Se muestra debajo de la hora cuando suena."
                ),
            ),
        },
        required=["when"],
    ),
    handler=_alarm_set,
)

register(
    name="alarm_cancel",
    description=(
        "Cancela un despertador activo. Llama cuando el usuario diga "
        "'cancela el despertador', 'quita la alarma de las 7', "
        "'borra el de mañana', etc. Pasa en `match` un trozo del "
        "label o el id si lo conoces; el backend hace coincidencia "
        "fuzzy. Si no es única la coincidencia, devuelve la lista de "
        "activos."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Etiqueta (o trozo) o id del despertador a cancelar.",
            ),
        },
        required=["match"],
    ),
    handler=_alarm_cancel,
)

register(
    name="alarm_list",
    description=(
        "Lista los despertadores activos y los muestra en pantalla. "
        "Llama cuando el usuario pregunte 'qué alarmas tengo', "
        "'qué despertadores hay puestos', 'a qué hora me avisas "
        "mañana', etc."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_alarm_list,
)


# ---- shopping list -------------------------------------------------


def _shopping_list_scene(items: list[shopping.ShoppingItem]) -> dict[str, Any]:
    return {"type": "shopping_list", "items": shopping.to_wire(items)}


async def _shopping_add(text: str) -> ToolResult:
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(shopping.add, text)
        items = await asyncio.to_thread(shopping.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("shopping_add failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    return ToolResult(
        response={
            "added": {"id": item.id, "text": item.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_list() -> ToolResult:
    items = await asyncio.to_thread(shopping.list_all)
    pending = [it for it in items if it.completed_ms is None]
    return ToolResult(
        response={
            "count": len(items),
            "pending": len(pending),
            "items": shopping.to_wire(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_complete(match: str) -> ToolResult:
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        completed = await asyncio.to_thread(shopping.complete, match)
        items = await asyncio.to_thread(shopping.list_all)
    except shopping.ShoppingItemNotFoundError:
        items = await asyncio.to_thread(shopping.list_all)
        return ToolResult(response={
            "error": (
                f"no hay nada en la compra que coincida con {match!r}. "
                f"Pendientes: {[it.text for it in items if it.completed_ms is None]}"
            ),
        })
    return ToolResult(
        response={
            "completed": {"id": completed.id, "text": completed.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_remove(match: str) -> ToolResult:
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(shopping.remove, match)
        items = await asyncio.to_thread(shopping.list_all)
    except shopping.ShoppingItemNotFoundError:
        items = await asyncio.to_thread(shopping.list_all)
        return ToolResult(response={
            "error": (
                f"no hay {match!r} en la compra. Existing: "
                f"{[it.text for it in items]}"
            ),
        })
    return ToolResult(
        response={
            "removed": {"id": removed.id, "text": removed.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_clear() -> ToolResult:
    """'Ya he hecho la compra' — vacía la lista entera."""
    cleared = await asyncio.to_thread(shopping.clear_all)
    return ToolResult(
        response={"cleared": cleared},
        scene=_shopping_list_scene([]),
    )


register(
    name="shopping_add",
    description=(
        "Añade un artículo a la lista de la compra. Llama cuando el "
        "usuario diga 'añade a la compra', 'apunta para comprar', "
        "'me hace falta X', 'cómprame X', etc. Pasa en `text` lo más "
        "literal que dijo (incluye cantidad si la mencionó: '2 litros "
        "de leche', 'un kilo de tomates'). NO confundir con todo_add: "
        "los TODOs son tareas/ideas; esto va al carro de la compra."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "text": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Artículo a añadir, con cantidad si la dijo el "
                    "usuario. Ej: 'leche', '2 litros de leche', "
                    "'pan integral'."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_shopping_add,
)

register(
    name="shopping_list",
    description=(
        "Devuelve y muestra la lista de la compra. Llama cuando el "
        "usuario pregunte 'qué tengo en la compra', 'qué hay que "
        "comprar', 'enséñame la lista de la compra', etc."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_shopping_list,
)

register(
    name="shopping_complete",
    description=(
        "Marca un artículo como ya comprado / metido en el carro. "
        "Llama cuando el usuario diga 'ya tengo X', 'tacha X', 'X "
        "ya lo cogí', 'metí X', etc. Coincidencia parcial sin acentos "
        "en `match`."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Trozo del nombre del artículo (o id).",
            ),
        },
        required=["match"],
    ),
    handler=_shopping_complete,
)

register(
    name="shopping_remove",
    description=(
        "Borra un artículo de la lista (no lo marca, lo elimina). "
        "Llama cuando el usuario diga 'quita X de la compra', 'borra "
        "X', 'al final no compres X', etc."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Trozo del nombre del artículo (o id) a borrar.",
            ),
        },
        required=["match"],
    ),
    handler=_shopping_remove,
)

register(
    name="shopping_clear",
    description=(
        "Vacía la lista de la compra entera. Llama SOLO cuando el "
        "usuario diga claramente 'ya he hecho la compra', 'vacía la "
        "compra', 'reinicia la lista', etc. — es destructivo, no se "
        "deshace."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_shopping_clear,
)


# ---- usage stats / costes ------------------------------------------


_VALID_USAGE_PERIODS = {"today", "7d", "30d"}


async def _usage_stats(period: str = "today") -> ToolResult:
    """Devuelve y muestra estadísticas de uso para un periodo."""
    if period not in _VALID_USAGE_PERIODS:
        return ToolResult(response={
            "error": (
                f"period inválido {period!r}. valores: "
                f"{sorted(_VALID_USAGE_PERIODS)}"
            ),
        })
    payload = await asyncio.to_thread(usage.aggregate, period)
    return ToolResult(
        response=payload,
        scene={"type": "usage_stats", **payload},
    )


async def _end_conversation() -> dict[str, Any]:
    """Marca la conversación para cierre tras este turno.

    No cerramos aquí mismo: lanzamos [SessionEndRequested] que
    [dispatch] re-lanza al caller (gemini.proxy). El proxy deja que
    Gemini termine de hablar y luego rompe el bucle, así Kiwi puede
    decir "hasta luego" sin que el WS se corte a media frase.
    """
    raise SessionEndRequested()


register(
    name="end_conversation",
    description=(
        "Termina la conversación con el usuario. Llama a este tool "
        "cuando entiendas que el usuario quiere acabar: 'nada más', "
        "'ya está', 'gracias', 'es todo', 'hasta luego', 'adiós', "
        "'vale ya', 'ok kiwi', o cualquier matiz contextual que "
        "indique cierre (también un agradecimiento final, una "
        "despedida implícita, etc.). Después de llamarlo, di una "
        "despedida MUY breve (1-3 palabras como 'Hasta luego' o "
        "'Vale, hasta luego') y para. El sistema cerrará la "
        "conversación tras tu respuesta sin esperar más entrada. "
        "NO llames a este tool si el usuario está en mitad de algo "
        "(añadiendo TODOs, pidiendo info, etc.) — sólo cuando el "
        "contexto deja claro que terminó."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_end_conversation,
)


# ---- volumen del tablet --------------------------------------------


async def _set_volume(
    level: int | None = None,
    delta: int | None = None,
    *,
    on_scene: SceneSink | None = None,
) -> ToolResult:
    """Ajusta el volumen multimedia del tablet (stream MUSIC)."""
    if level is None and delta is None:
        return ToolResult(
            response={"error": "pasa level (0-100) o delta (-100..+100)"},
        )
    if level is not None and not 0 <= int(level) <= 100:
        return ToolResult(
            response={"error": f"level fuera de 0-100: {level}"},
        )
    if delta is not None and not -100 <= int(delta) <= 100:
        return ToolResult(
            response={"error": f"delta fuera de -100..+100: {delta}"},
        )

    payload: dict[str, Any] = {
        "type": "device_command",
        "command": "set_volume",
    }
    if level is not None:
        payload["level"] = int(level)
    if delta is not None:
        payload["delta"] = int(delta)

    if on_scene is not None:
        try:
            await on_scene(payload)
        except Exception:  # noqa: BLE001
            log.exception("device_command set_volume push failed")
    return ToolResult(response={"ok": True, **payload})


register(
    name="set_volume",
    description=(
        "Ajusta el volumen multimedia del tablet (música, vídeo, "
        "alarmas — todo va al mismo stream). Pasa SÓLO uno de los "
        "dos parámetros:\n"
        "  - `level` (0-100): volumen absoluto. 0 = silencio, 100 = "
        "máximo. Para 'silencia' / 'mute' usa level=0; para "
        "'volumen al 50%' usa level=50.\n"
        "  - `delta` (-100..+100): subir/bajar en puntos sobre el "
        "valor actual. Para 'sube el volumen' usa delta=15; para "
        "'baja un poco' usa delta=-10; para 'mucho más alto' usa "
        "delta=30. Ajusta la magnitud al matiz del usuario."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "level": types.Schema(
                type=types.Type.INTEGER,
                description="Volumen absoluto 0-100. Combinable con delta=None.",
            ),
            "delta": types.Schema(
                type=types.Type.INTEGER,
                description=(
                    "Cambio relativo en puntos (-100..+100). Positivo "
                    "= sube, negativo = baja."
                ),
            ),
        },
    ),
    handler=_set_volume,
)


register(
    name="usage_stats",
    description=(
        "Devuelve y muestra cuánto se ha usado Kiwi en un periodo: "
        "número de conversaciones, minutos de audio, top de tools "
        "usadas y coste estimado en EUR. Llama a este tool cuando el "
        "usuario pregunte 'cuánto llevamos gastando', 'cuánto te he "
        "usado hoy', 'qué uso le has dado esta semana', 'qué tools "
        "uso más', etc. Por defecto 'today'; usa '7d' / '30d' para "
        "ventanas más amplias."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "period": types.Schema(
                type=types.Type.STRING,
                description=(
                    "'today' (default), '7d' o '30d'. Si el usuario "
                    "dice 'esta semana' usa 7d; 'este mes' / 'últimos "
                    "30 días' usa 30d."
                ),
            ),
        },
    ),
    handler=_usage_stats,
)


register(
    name="get_weather",
    description=(
        "Devuelve el tiempo ACTUAL (temperatura y descripción breve) en "
        "la ubicación del usuario. Llama a este tool cuando pregunte "
        "'qué tiempo hace ahora', 'hace frío', 'está lloviendo'. Para "
        "preguntas sobre cómo va a estar el día, mañana o un día "
        "concreto usa get_weather_forecast en su lugar."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_get_weather,
)


async def _get_weather_forecast(date: str = "") -> dict[str, Any]:
    """Return per-day forecast for an ISO date (YYYY-MM-DD)."""
    if not date:
        return {"error": "date required (formato YYYY-MM-DD)"}
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        return {"error": f"date debe ser YYYY-MM-DD; recibido {date!r}"}
    snap = await asyncio.to_thread(weather.forecast, date)
    if snap is None:
        available = await asyncio.to_thread(weather.forecast_dates)
        return {
            "error": (
                f"sin previsión para {date}. Fechas disponibles: {available}"
            ),
        }
    return weather.forecast_to_wire(snap)


register(
    name="get_weather_forecast",
    description=(
        "Devuelve la previsión del tiempo para una fecha: máxima, "
        "mínima, condición dominante, probabilidad de lluvia, salida "
        "y puesta del sol, y un desglose hora a hora. Llama a este "
        "tool cuando el usuario pregunte 'qué tiempo va a hacer hoy', "
        "'cómo va a estar mañana', 'va a llover esta tarde', 'qué "
        "tiempo hará el viernes', '¿me llevo abrigo?', etc. Si "
        "pregunta por una franja del día (mañana / tarde / noche / a "
        "las X) usa el array `hourly` para responder con precisión; "
        "si pregunta por el día en general usa el resumen diario "
        "(max, min, descripción)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "date": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Fecha en formato ISO YYYY-MM-DD. Convierte la "
                    "petición ('hoy', 'mañana', 'el viernes', 'el día "
                    "12') a fecha concreta. Cubre hoy + 6 días."
                ),
            ),
        },
        required=["date"],
    ),
    handler=_get_weather_forecast,
)


register(
    name="todo_remove",
    description=(
        "Borra una tarea de la lista. Llama a este tool cuando el "
        "usuario diga 'borra X', 'quita X de la lista', 'elimina X' "
        "o similar. Mismo sistema de matching que todo_complete: "
        "pasa un trozo del texto o el id. Si la diferencia entre 'ya "
        "lo hice' y 'bórralo' no está clara en la conversación, "
        "prefiere todo_complete (deja rastro de la tarea hecha)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del texto de la tarea (o su id) a borrar."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_todo_remove,
)


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
