import asyncio
import logging
import secrets
import time
from datetime import UTC, datetime
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

import requests
from fastapi import FastAPI, Header, HTTPException, Request, WebSocket
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field

from . import (
    alarms,
    google_auth,
    log_buffer,
    shopping,
    spotify_service,
    spotify_stream,
    timer,
    todos,
    tools,
    usage,
    weather,
)
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

    # Calendar: today's events. Errors surface to the tablet so it
    # can show "Agenda no disponible" instead of pretending the day
    # is empty (silent degradation hid OAuth revocations for days).
    events_today: list[dict] = []
    events_today_error: str | None = None
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
    except tools.google_auth.GoogleAuthUnavailableError as exc:
        events_today_error = str(exc)
        logging.getLogger(__name__).info(
            "home: calendar unavailable (auth): %s", exc,
        )
    except Exception as exc:  # noqa: BLE001
        events_today_error = f"{type(exc).__name__}: {exc}"
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
    # El home ahora también muestra el pronóstico del día (max/min +
    # probabilidad de lluvia) para que no haya que abrir la scene de
    # tiempo para saber si va a llover, así que le adjuntamos la
    # entrada del daily forecast correspondiente a "hoy".
    current_weather: dict | None = None
    try:
        snap = await asyncio.to_thread(weather.current)
        if snap is not None:
            current_weather = weather.to_wire(snap)
            today_iso = datetime.now(ZoneInfo(tools.DEFAULT_TIMEZONE)).date().isoformat()
            today_forecast = await asyncio.to_thread(weather.forecast, today_iso)
            if today_forecast is not None:
                current_weather["temp_max_c"] = round(today_forecast.temp_max_c, 1)
                current_weather["temp_min_c"] = round(today_forecast.temp_min_c, 1)
                current_weather["precipitation_probability_max"] = (
                    today_forecast.precipitation_probability_max
                )
                current_weather["sunrise"] = today_forecast.sunrise
                current_weather["sunset"] = today_forecast.sunset
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
        "events_today_error": events_today_error,
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


@app.get("/api/stats")
async def get_stats(period: str = "today", token: str = "") -> dict[str, object]:
    """Aggregated usage stats por periodo (today / 7d / 30d)."""
    _require_dev_token(token)
    if period not in ("today", "7d", "30d"):
        raise HTTPException(
            status_code=400,
            detail=f"period debe ser today / 7d / 30d (recibido: {period!r})",
        )
    return await asyncio.to_thread(usage.aggregate, period)


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


# ---------- Spotify REST + SSE ---------------------------------------
#
# Estos endpoints sirven al tablet para controlar Spotify *sin* pasar
# por Gemini Live. Un POST tiene <200 ms vs >1 s del round-trip por
# tool — y no consume tokens. Todos comparten el mismo dev_token que
# el resto de endpoints de tablet (igual de ámbito y rotación).
#
# La capa de business logic vive en ``spotify_service``; aquí sólo
# traducimos errores estructurados ``{"error": "...", "status": N}``
# a ``HTTPException``.


def _spotify_response(payload: dict[str, object]) -> dict[str, object]:
    """Lanza HTTPException si el service marcó error; si no, devuelve payload."""
    if isinstance(payload, dict) and "error" in payload:
        status = int(payload.get("status") or 502)
        # Quita el "status" del cuerpo para no doble-exponerlo.
        body = {k: v for k, v in payload.items() if k != "status"}
        raise HTTPException(status_code=status, detail=body)
    return payload


@app.get("/api/spotify/state")
async def spotify_state(token: str = "") -> dict[str, object]:
    """Snapshot completo del player. Siempre 200 — el cuerpo dice si
    hay datos disponibles vía ``available`` y/o ``playing``."""
    _require_dev_token(token)
    return await spotify_service.current_state()


@app.get("/api/spotify/devices")
async def spotify_devices(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return _spotify_response(await spotify_service.list_devices())


@app.get("/api/spotify/search")
async def spotify_search(
    q: str = "", kind: str = "track", limit: int = 20, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return _spotify_response(await spotify_service.search(q, kind, limit))


@app.post("/api/spotify/play")
async def spotify_play(
    uri: str = "", query: str = "", token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return _spotify_response(
        await spotify_service.play(uri or None, query or None),
    )


@app.post("/api/spotify/pause")
async def spotify_pause(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.pause()


@app.post("/api/spotify/resume")
async def spotify_resume(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.resume()


@app.post("/api/spotify/next")
async def spotify_next(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.next_track()


@app.post("/api/spotify/previous")
async def spotify_previous(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.previous_track()


@app.post("/api/spotify/seek")
async def spotify_seek(position_ms: int = 0, token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.seek(position_ms)


@app.post("/api/spotify/shuffle")
async def spotify_shuffle(enabled: bool = True, token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.shuffle(enabled)


@app.post("/api/spotify/repeat")
async def spotify_repeat(mode: str = "off", token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.repeat(mode)


@app.post("/api/spotify/volume")
async def spotify_volume(percent: int = 50, token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.set_volume(percent)


@app.post("/api/spotify/transfer")
async def spotify_transfer(
    device_id: str = "", target: str = "", token: str = "",
) -> dict[str, object]:
    """Transfiere playback. Acepta ``device_id`` (exacto) O ``target``
    (fuzzy-match contra el nombre, p.ej. 'tablet')."""
    _require_dev_token(token)
    if device_id:
        return _spotify_response(await spotify_service.transfer(device_id))
    if target:
        return _spotify_response(await spotify_service.transfer_to_name(target))
    raise HTTPException(status_code=400, detail="provide device_id or target")


@app.post("/api/spotify/like")
async def spotify_like(uri: str = "", token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.like(uri or None)


@app.post("/api/spotify/unlike")
async def spotify_unlike(uri: str = "", token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.unlike(uri or None)


@app.get("/api/spotify/queue")
async def spotify_queue(token: str = "") -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.queue()


@app.post("/api/spotify/queue")
async def spotify_queue_add(
    uri: str = "", query: str = "", token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return _spotify_response(
        await spotify_service.add_to_queue(uri or None, query or None),
    )


@app.get("/api/spotify/library/playlists")
async def spotify_library_playlists(
    limit: int = 20, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.my_playlists(limit)


@app.get("/api/spotify/library/recently_played")
async def spotify_library_recent(
    limit: int = 20, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.recently_played(limit)


@app.get("/api/spotify/library/liked")
async def spotify_library_liked(
    limit: int = 30, offset: int = 0, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.liked_songs(limit, offset)


@app.get("/api/spotify/library/featured")
async def spotify_library_featured(
    limit: int = 12, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.featured_playlists(limit)


@app.get("/api/spotify/library/top_tracks")
async def spotify_library_top_tracks(
    limit: int = 20, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.top_tracks(limit)


@app.get("/api/spotify/library/top_artists")
async def spotify_library_top_artists(
    limit: int = 20, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return await spotify_service.top_artists(limit)


@app.get("/api/spotify/playlist")
async def spotify_playlist_tracks(
    uri: str = "", limit: int = 50, token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    if not uri:
        raise HTTPException(status_code=400, detail="uri required")
    return _spotify_response(
        await spotify_service.playlist_tracks(uri, limit),
    )


@app.get("/api/spotify/recommend")
async def spotify_recommend(
    seed_track: str = "",
    seed_artist: str = "",
    seed_genre: str = "",
    mood: str = "",
    limit: int = 20,
    token: str = "",
) -> dict[str, object]:
    _require_dev_token(token)
    return _spotify_response(
        await spotify_service.recommend(
            seed_track or None, seed_artist or None,
            seed_genre or None, mood or None, limit,
        ),
    )


@app.get("/api/spotify/stream")
async def spotify_stream_endpoint(token: str = ""):
    """SSE stream con el player state. Multiplexado por el state-pump."""
    _require_dev_token(token)
    from fastapi.responses import StreamingResponse
    headers = {
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",  # nginx (si lo hubiera) deja pasar sin buffer
        "Connection": "keep-alive",
    }
    return StreamingResponse(
        spotify_stream.event_stream(),
        media_type="text/event-stream",
        headers=headers,
    )


# ---------- Google OAuth web flow -----------------------------------
#
# Cuando el refresh token de Google caduca / se revoca, el backend
# se queda sin agenda (y sin tools de Calendar/YouTube). Antes había
# que correr ``tools/oauth-bootstrap/`` en local; ahora ofrecemos
# un flujo web que el usuario abre en cualquier navegador (incluido
# el del tablet, vía el botón "Renovar Google").
#
# Flujo:
#   GET /oauth/google/start?token=DEV  → redirige a accounts.google.com
#   GET /oauth/google/callback?code=…  → intercambia, guarda en GCS
#
# La pieza sensible es que Google exige que ``redirect_uri`` esté
# pre-registrada en la consola del OAuth client. Hay que añadir
# ``https://<cloud-run-url>/oauth/google/callback`` a Authorized
# redirect URIs del client_secret.json original.

_OAUTH_STATES: dict[str, float] = {}
_OAUTH_STATE_TTL_S = 600.0  # 10 min


def _oauth_callback_url(request: Request | None) -> str:
    """Build the redirect_uri Google compares against el registrado.

    Cloud Run termina TLS en el LB y le pasa al contenedor la petición
    como HTTP, así que ``request.base_url`` devuelve ``http://`` por
    defecto y Google rechaza con ``redirect_uri_mismatch``. Aunque ya
    ejecutamos uvicorn con ``--proxy-headers`` para que respete
    X-Forwarded-Proto, forzamos https aquí también como defensa
    explícita — el coste de equivocarse en este endpoint es romper
    el único flujo de renovación, no merece la pena depender solo
    de la config de uvicorn.
    """
    if request is None:
        return "/oauth/google/callback"
    base = str(request.base_url).rstrip("/")
    # Forzar https si el LB nos pasó http — los hosts que no son
    # localhost siempre van por https en Cloud Run.
    if base.startswith("http://") and "localhost" not in base:
        base = "https://" + base[len("http://"):]
    return f"{base}/oauth/google/callback"


def _prune_oauth_states() -> None:
    """Drop expired entries so the in-memory dict no crece sin parar."""
    now = time.time()
    expired = [k for k, ts in _OAUTH_STATES.items() if now - ts > _OAUTH_STATE_TTL_S]
    for k in expired:
        _OAUTH_STATES.pop(k, None)


@app.get("/oauth/google/start")
async def oauth_google_start(token: str = "", request: Request = None):  # type: ignore[assignment]
    """Inicia el flujo OAuth — redirige al consent de Google."""
    _require_dev_token(token)
    try:
        cfg = google_auth.client_config()
    except google_auth.GoogleAuthUnavailableError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    _prune_oauth_states()
    state = secrets.token_urlsafe(24)
    _OAUTH_STATES[state] = time.time()

    redirect_uri = _oauth_callback_url(request)
    auth_endpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    params = {
        "client_id": cfg["client_id"],
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": " ".join(cfg["scopes"]),
        # ``access_type=offline`` + ``prompt=consent`` fuerza a Google
        # a emitir refresh_token incluso si el usuario ya había dado
        # consent antes — sin esto el callback recibe access_token
        # pero no refresh_token, que es justo lo que necesitamos.
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return RedirectResponse(f"{auth_endpoint}?{urlencode(params)}")


@app.get("/oauth/google/callback")
async def oauth_google_callback(
    code: str = "", state: str = "", error: str = "", request: Request = None,  # type: ignore[assignment]
):
    """Recibe el code de Google, lo intercambia y guarda el bundle."""
    if error:
        return HTMLResponse(
            _oauth_html("Error de Google", f"Google devolvió un error: {error}"),
            status_code=400,
        )
    if not code or not state:
        raise HTTPException(status_code=400, detail="missing code/state")
    _prune_oauth_states()
    if _OAUTH_STATES.pop(state, None) is None:
        raise HTTPException(status_code=400, detail="invalid or expired state")

    try:
        cfg = google_auth.client_config()
    except google_auth.GoogleAuthUnavailableError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    redirect_uri = _oauth_callback_url(request)
    try:
        response = await asyncio.to_thread(
            requests.post,
            cfg["token_uri"],
            data={
                "code": code,
                "client_id": cfg["client_id"],
                "client_secret": cfg["client_secret"],
                "redirect_uri": redirect_uri,
                "grant_type": "authorization_code",
            },
            timeout=15,
        )
    except requests.RequestException as exc:
        return HTMLResponse(
            _oauth_html("Error de red", f"No se pudo contactar a Google: {exc}"),
            status_code=502,
        )
    if not response.ok:
        return HTMLResponse(
            _oauth_html(
                "Google rechazó el code",
                f"Status {response.status_code}: {response.text[:300]}",
            ),
            status_code=502,
        )
    payload = response.json()
    refresh_token = payload.get("refresh_token")
    if not refresh_token:
        # Sin refresh_token estamos peor que antes — Google sólo lo
        # emite cuando access_type=offline+prompt=consent y el usuario
        # no lo había concedido antes. Surface el error en vez de
        # guardar un bundle incompleto.
        return HTMLResponse(
            _oauth_html(
                "Sin refresh_token",
                "Google devolvió access_token pero no refresh_token. "
                "Probable: el client no pide access_type=offline. "
                "Revisa la configuración del OAuth client.",
            ),
            status_code=500,
        )

    new_bundle = {
        "refresh_token": refresh_token,
        "client_id": cfg["client_id"],
        "client_secret": cfg["client_secret"],
        "token_uri": cfg["token_uri"],
        "scopes": cfg["scopes"],
    }
    try:
        await asyncio.to_thread(google_auth.save_bundle, new_bundle)
    except Exception as exc:  # noqa: BLE001
        return HTMLResponse(
            _oauth_html("Error guardando", f"{type(exc).__name__}: {exc}"),
            status_code=500,
        )
    # Fuerza un primer refresh para que la cache quede caliente y
    # cualquier error de credenciales malformadas explote aquí, no
    # en el siguiente /api/home del tablet.
    try:
        await asyncio.to_thread(google_auth.credentials)
    except google_auth.GoogleAuthUnavailableError as exc:
        return HTMLResponse(
            _oauth_html(
                "Guardado pero refresh falló",
                f"El bundle se persistió pero el primer refresh dio: {exc}",
            ),
            status_code=500,
        )
    return HTMLResponse(
        _oauth_html(
            "Listo",
            "Refresh token renovado correctamente. Puedes cerrar esta "
            "pestaña y volver al tablet — la agenda volverá a aparecer "
            "en la siguiente actualización.",
        ),
    )


def _oauth_html(title: str, body: str) -> str:
    """Tiny self-contained HTML for the OAuth result page."""
    return (
        f"<!doctype html><html lang='es'><head><meta charset='utf-8'>"
        f"<title>Kiwi · {title}</title>"
        "<style>body{font-family:system-ui,sans-serif;background:#0a0a0a;"
        "color:#eee;display:flex;min-height:100vh;align-items:center;"
        "justify-content:center;margin:0;padding:24px;box-sizing:border-box}"
        ".card{max-width:540px;background:#111;border:1px solid #222;"
        "border-radius:16px;padding:32px}"
        "h1{margin:0 0 16px;font-weight:300;font-size:32px}"
        "p{line-height:1.5;opacity:0.85}</style></head><body>"
        f"<div class='card'><h1>{title}</h1><p>{body}</p></div>"
        "</body></html>"
    )


@app.websocket("/ws/session")
async def ws_session(websocket: WebSocket) -> None:
    await run_session(websocket)
