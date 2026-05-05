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
