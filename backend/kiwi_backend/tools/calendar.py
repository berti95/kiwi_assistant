"""Tools relacionadas con el calendario de Google del usuario."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from google.genai import types
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from .. import google_auth
from .registry import DEFAULT_TIMEZONE, ToolResult, register

log = logging.getLogger(__name__)

# Lower-cased period values accepted by ``calendar_list_events``. Kept
# small + opinionated so Gemini doesn't have to guess what an arbitrary
# free-form string means.
_CALENDAR_PERIODS = {"today", "tomorrow", "this_week", "next_7_days"}


def _calendar_window(
    period: str, now: datetime,
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
