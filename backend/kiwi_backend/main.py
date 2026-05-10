import asyncio
import logging
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from fastapi import FastAPI, Header, HTTPException, WebSocket
from pydantic import BaseModel, Field

from . import alarms, log_buffer, shopping, timer, todos, tools, weather
from .auth import is_valid_api_key
from .session import run_session
from .settings import settings

# Cloud Run's log capture relies on stdout/stderr, but Python's root
# logger defaults to WARNING which hides our per-turn INFO traces. Bump
# the kiwi_backend tree to INFO so multi-turn diagnostics show up in
# Cloud Logging without affecting noisy third-party libs.
logging.basicConfig(level=logging.INFO)
logging.getLogger("kiwi_backend").setLevel(logging.INFO)

# Mirror every kiwi_backend.* log record into an in-memory ring buffer so
# the developer can poll recent lines via GET /api/logs/recent without
# fetching from Cloud Logging.
log_buffer.install_handler()

# Dedicated logger for tablet-shipped lines so we can grep them out
# from the rest of the backend traffic when debugging.
tablet_log = logging.getLogger("kiwi_backend.tablet")

app = FastAPI(title="Kiwi backend", version=settings.app_version_name)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/version")
async def version() -> dict[str, object]:
    return {
        "version_code": settings.app_version_code,
        "version_name": settings.app_version_name,
        "apk_url": settings.apk_url,
    }


class _LogEntry(BaseModel):
    """One line of the tablet's app log, as shipped by ``LogShipper``."""

    ts_ms: int
    level: str = Field(min_length=1, max_length=4)
    tag: str = Field(min_length=1, max_length=64)
    message: str
    error: str | None = None


class _LogBatch(BaseModel):
    """A batch of log entries with the device's identity envelope."""

    device_id: str = Field(default="kiwi-tablet", max_length=64)
    version_code: int | None = None
    version_name: str | None = None
    entries: list[_LogEntry]


@app.post("/api/logs")
async def post_logs(
    batch: _LogBatch,
    x_api_key: str = Header(default=""),
) -> dict[str, int]:
    """Receive a batch of log lines from the tablet and dump them into
    Cloud Logging via stdout. Auth is the same shared API key the
    tablet uses for the WebSocket session.

    Cheap: a 200-line batch every 5 s is ~40 lines/s on the busy
    case, well below Cloud Logging's per-instance free tier.
    """
    if not is_valid_api_key(x_api_key):
        raise HTTPException(status_code=403, detail="invalid api key")

    header = ""
    if batch.version_name:
        header = f" v{batch.version_name}({batch.version_code})"

    for entry in batch.entries:
        ts = datetime.fromtimestamp(entry.ts_ms / 1000, tz=UTC).isoformat()
        line = (
            f"[{batch.device_id}{header} @ {ts}] "
            f"{entry.level}/{entry.tag}: {entry.message}"
        )
        if entry.error:
            line += f"\n{entry.error}"
        tablet_log.info(line)

    return {"received": len(batch.entries)}


@app.get("/api/logs/recent")
async def get_recent_logs(
    token: str = "",
    since: int = 0,
    limit: int = 500,
) -> dict[str, object]:
    """Return the latest log lines kept in memory.

    Auth: a separate ``DEV_LOGS_TOKEN`` env var (NOT the tablet's API
    key — different scope). Closed by default: an empty token in the
    settings means every request gets 403, so a forgotten production
    deploy doesn't accidentally expose the buffer.

    Pagination: pass back the ``next_since`` cursor from the previous
    response to receive only entries that are newer than what you've
    already seen. ``since=0`` returns the oldest still-buffered entry.
    """
    expected = settings.dev_logs_token
    if not expected or token != expected:
        raise HTTPException(status_code=403, detail="invalid dev token")
    capped_limit = max(1, min(limit, log_buffer.CAPACITY))
    entries, cursor = log_buffer.snapshot_since(since, capped_limit)
    return {
        "entries": entries,
        "count": len(entries),
        "next_since": cursor,
    }


def _require_dev_token(token: str) -> None:
    """Reject non-matching dev tokens with 403. Same gate as /api/logs/recent."""
    expected = settings.dev_logs_token
    if not expected or token != expected:
        raise HTTPException(status_code=403, detail="invalid dev token")


@app.get("/api/todos")
async def get_todos(token: str = "") -> dict[str, object]:
    """Return the user's TODO list. Dev-token gated.

    Closed by default (empty token in settings → every request 403).
    The same token already protects ``/api/logs/recent``; reusing it
    means there's no new secret to provision.
    """
    _require_dev_token(token)
    items = await asyncio.to_thread(todos.list_all)
    return {"items": todos.to_wire(items)}


@app.post("/api/todos/{todo_id}/complete")
async def complete_todo(todo_id: str, token: str = "") -> dict[str, object]:
    """Mark a TODO as completed. Used by the tablet's tap-to-check
    action, complementary to the ``todo_complete`` voice tool."""
    _require_dev_token(token)
    try:
        await asyncio.to_thread(todos.complete, todo_id)
    except todos.TodoNotFoundError as exc:
        raise HTTPException(
            status_code=404, detail=f"todo not found: {todo_id}",
        ) from exc
    items = await asyncio.to_thread(todos.list_all)
    return {"items": todos.to_wire(items)}


@app.post("/api/timer/cancel")
async def cancel_timer(token: str = "") -> dict[str, object]:
    """Drop the currently-active timer. Used by the tablet's TimerScene
    Cancelar/Apagar button so a tap-cancel keeps voice queries
    ('cuánto queda') honest about there being no timer."""
    _require_dev_token(token)
    cancelled = await asyncio.to_thread(timer.cancel)
    return {
        "cancelled": cancelled is not None,
        "label": cancelled.label if cancelled is not None else "",
    }


@app.post("/api/todos/{todo_id}/remove")
async def remove_todo(todo_id: str, token: str = "") -> dict[str, object]:
    """Delete a TODO outright. Used by the tablet to drop a completed
    item with a second tap."""
    _require_dev_token(token)
    try:
        await asyncio.to_thread(todos.remove, todo_id)
    except todos.TodoNotFoundError as exc:
        raise HTTPException(
            status_code=404, detail=f"todo not found: {todo_id}",
        ) from exc
    items = await asyncio.to_thread(todos.list_all)
    return {"items": todos.to_wire(items)}


@app.get("/api/home")
async def get_home(token: str = "") -> dict[str, object]:
    """Aggregate snapshot the tablet renders on the Idle/Home scene.

    Composed of three parts:
      - ``events_today``: today's calendar (uses the same blocking
        helper the calendar tool does).
      - ``todos``: full TODO list, both pending and completed.
      - ``now_playing``: optional Spotify-currently-playing card.

    Each subsystem failing degrades only its own part — Spotify down
    or Calendar misconfigured shouldn't blank the whole home screen.
    """
    _require_dev_token(token)

    # Calendar: today's events.
    events_today: list[dict] = []
    try:
        creds = await asyncio.to_thread(tools.google_auth.credentials)
        tz = ZoneInfo(tools.DEFAULT_TIMEZONE)
        now = datetime.now(tz)
        window = tools._calendar_window("today", now)
        if window is not None:
            time_min, time_max = window
            events_today = await asyncio.to_thread(
                tools._list_events_blocking, creds, time_min, time_max, 10,
            )
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).info(
            "home: calendar unavailable: %s: %s", type(exc).__name__, exc,
        )

    # TODOs: all of them; client decides what to show.
    todo_items: list[dict] = []
    try:
        loaded = await asyncio.to_thread(todos.list_all)
        todo_items = todos.to_wire(loaded)
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).info(
            "home: todos unavailable: %s: %s", type(exc).__name__, exc,
        )

    # Now playing: optional. _spotify_currently_playing_blocking
    # already swallows API errors and returns (response, None) in
    # that case; we surface ``None`` to the client either way.
    now_playing: dict | None = None
    try:
        _, scene = await asyncio.to_thread(tools._spotify_currently_playing_blocking)
        # The scene payload already has the right shape for the chip
        # (title, artist, album_art_url, …); strip the wire-type field
        # the WS pipeline uses.
        if scene is not None and scene.get("is_playing"):
            now_playing = {
                "title": scene.get("title", ""),
                "artist": scene.get("artist", ""),
                "album_art_url": scene.get("album_art_url"),
            }
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).info(
            "home: spotify unavailable: %s: %s", type(exc).__name__, exc,
        )

    # Weather: cached at the module level (10 min TTL) so this is a
    # ~no-op most of the time; failures degrade to None.
    current_weather: dict | None = None
    try:
        snap = await asyncio.to_thread(weather.current)
        if snap is not None:
            current_weather = weather.to_wire(snap)
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).info(
            "home: weather unavailable: %s: %s", type(exc).__name__, exc,
        )

    # Alarms: full active list so the tablet can reconcile its
    # AlarmManager scheduling. Failures degrade to empty.
    alarm_items: list[dict] = []
    try:
        alarm_items = alarms.to_wire(await asyncio.to_thread(alarms.list_active))
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).info(
            "home: alarms unavailable: %s: %s", type(exc).__name__, exc,
        )

    return {
        "events_today": events_today,
        "todos": todo_items,
        "now_playing": now_playing,
        "weather": current_weather,
        "alarms": alarm_items,
    }


@app.get("/api/shopping")
async def get_shopping(token: str = "") -> dict[str, object]:
    """Read-only listing of the shopping list (dev-token gated)."""
    _require_dev_token(token)
    items = await asyncio.to_thread(shopping.list_all)
    return {"items": shopping.to_wire(items)}


@app.post("/api/shopping/{item_id}/complete")
async def complete_shopping(item_id: str, token: str = "") -> dict[str, object]:
    """Mark a shopping item as bought (tablet tap-to-check path)."""
    _require_dev_token(token)
    try:
        await asyncio.to_thread(shopping.complete, item_id)
    except shopping.ShoppingItemNotFoundError as exc:
        raise HTTPException(
            status_code=404, detail=f"shopping item not found: {item_id}",
        ) from exc
    items = await asyncio.to_thread(shopping.list_all)
    return {"items": shopping.to_wire(items)}


@app.post("/api/shopping/{item_id}/remove")
async def remove_shopping(item_id: str, token: str = "") -> dict[str, object]:
    """Delete a shopping item (second tap on completed)."""
    _require_dev_token(token)
    try:
        await asyncio.to_thread(shopping.remove, item_id)
    except shopping.ShoppingItemNotFoundError as exc:
        raise HTTPException(
            status_code=404, detail=f"shopping item not found: {item_id}",
        ) from exc
    items = await asyncio.to_thread(shopping.list_all)
    return {"items": shopping.to_wire(items)}


@app.post("/api/shopping/clear")
async def clear_shopping(token: str = "") -> dict[str, object]:
    """Clear the entire shopping list ("ya he hecho la compra")."""
    _require_dev_token(token)
    cleared = await asyncio.to_thread(shopping.clear_all)
    return {"cleared": cleared, "items": []}


@app.post("/api/alarms/{alarm_id}/dismiss")
async def dismiss_alarm(alarm_id: str, token: str = "") -> dict[str, object]:
    """Drop an alarm after the tablet rang it (Apagar button)."""
    _require_dev_token(token)
    cancelled = await asyncio.to_thread(alarms.dismiss, alarm_id)
    items = await asyncio.to_thread(alarms.list_active)
    return {
        "dismissed": cancelled is not None,
        "label": cancelled.label if cancelled is not None else "",
        "items": alarms.to_wire(items),
    }


@app.post("/api/alarms/{alarm_id}/snooze")
async def snooze_alarm(
    alarm_id: str, minutes: int = 10, token: str = "",
) -> dict[str, object]:
    """Push an alarm forward by ``minutes`` (Posponer button)."""
    _require_dev_token(token)
    if minutes <= 0:
        raise HTTPException(status_code=400, detail="minutes must be positive")
    try:
        updated = await asyncio.to_thread(alarms.snooze, alarm_id, minutes)
    except alarms.AlarmNotFoundError as exc:
        raise HTTPException(status_code=404, detail=f"alarm not found: {alarm_id}") from exc
    items = await asyncio.to_thread(alarms.list_active)
    return {
        "snoozed": True,
        "fires_at_ms": updated.fires_at_ms,
        "items": alarms.to_wire(items),
    }


@app.websocket("/ws/session")
async def ws_session(websocket: WebSocket) -> None:
    await run_session(websocket)
