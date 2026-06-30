"""Tools de Spotify: estado, búsqueda, control y transferencia entre devices.

Este módulo contiene los **helpers de bajo nivel** (HTTP + token + DTOs +
operaciones blocking) que el resto del sistema reutiliza. Tres familias
de callers entran aquí:

  • Las propias *Gemini tools* registradas al final del archivo.
  • ``kiwi_backend.spotify_service`` — capa de servicio que envuelve
    estos helpers en operaciones de negocio reusables y los expone a
    los endpoints HTTP REST (``/api/spotify/*``) y al state-pump SSE.
  • Tests, que hacen ``monkeypatch.setattr(tools.spotify, "...", ...)``
    sobre los nombres concretos (``_spotify_devices_blocking``, etc.).

Las funciones blocking se mantienen aquí (no se mueven al service)
precisamente porque los tests monkeypatchean estos nombres; mover
rompería la suite. El service importa desde aquí y deja la inversión
de la dependencia para futuro si nos hace falta.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import requests
from google.genai import types

from .. import spotify_auth
from .registry import SceneSink, ToolResult, register

log = logging.getLogger(__name__)

SPOTIFY_API_BASE = "https://api.spotify.com/v1"

# Spotify Premium-only error body marker. Devuelto como 403 con
# ``reason: "PREMIUM_REQUIRED"`` en el cuerpo cuando intentamos
# operar playback con una cuenta free. Lo detectamos para dar un
# error claro en vez del genérico "spotify api error: 403".
_PREMIUM_REQUIRED_MARKER = "PREMIUM_REQUIRED"


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
    """PUT/POST/DELETE helper. Returns {} on 204 (Spotify's "ok empty").

    Detecta tres errores semánticamente importantes y los promueve a
    excepciones específicas en lugar de un genérico ``HTTPError``:

    * 404 con "device" en el body → :class:`SpotifyNoDeviceError`.
    * 403 con ``PREMIUM_REQUIRED`` en el body → :class:`SpotifyPremiumRequiredError`.
    * 429 (rate limit) → :class:`SpotifyRateLimitedError` con
      ``retry_after`` extraído de la cabecera.
    """
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
        body_text = response.text or ""
        # Spotify-specific 404: "no active device". Surface a friendly
        # message that Gemini can relay verbatim instead of "404".
        if response.status_code == 404 and "device" in body_text.lower():
            raise SpotifyNoDeviceError()
        if (
            response.status_code == 403
            and _PREMIUM_REQUIRED_MARKER in body_text
        ):
            raise SpotifyPremiumRequiredError()
        if response.status_code == 429:
            retry_raw = response.headers.get("Retry-After", "1")
            try:
                retry_after = max(1, int(retry_raw))
            except ValueError:
                retry_after = 1
            raise SpotifyRateLimitedError(retry_after)
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


class SpotifyPremiumRequiredError(RuntimeError):
    """The user's Spotify account is not Premium — playback control rejected."""


class SpotifyRateLimitedError(RuntimeError):
    """Spotify returned 429. ``retry_after`` is the seconds to wait."""

    def __init__(self, retry_after: int) -> None:
        super().__init__(f"rate limited; retry after {retry_after}s")
        self.retry_after = retry_after


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
    """Build the wire-format NowPlaying scene from /me/player payload.

    Wire-format extendido con respecto a la V1 inicial: además del track
    + estado de play, ahora viajan shuffle, repeat, device + volumen y
    ``track_uri`` para que el cliente pueda pedir like / unlike sin
    tener que llamar de vuelta. Los campos nuevos son siempre opcionales
    en el lado cliente — versiones anteriores del tablet siguen
    parseando lo que entienden.
    """
    track = _spotify_track_dto(payload.get("item") if payload else None)
    if not track:
        return None
    device = (payload or {}).get("device") or {}
    return {
        "type": "now_playing",
        "title": track["title"],
        "artist": track["artist"],
        "album": track["album"],
        "album_art_url": track["album_art_url"],
        "is_playing": is_playing,
        "duration_ms": track["duration_ms"],
        "progress_ms": (payload or {}).get("progress_ms") or 0,
        "track_uri": track["uri"],
        "shuffle": bool((payload or {}).get("shuffle_state")),
        "repeat_state": (payload or {}).get("repeat_state") or "off",
        "device": {
            "id": device.get("id"),
            "name": device.get("name") or "",
            "type": (device.get("type") or "").lower(),
            "is_active": bool(device.get("is_active")),
            "volume_percent": device.get("volume_percent"),
            "supports_volume": bool(device.get("supports_volume")),
        } if device else None,
    }


def _spotify_full_state_blocking() -> dict[str, Any]:
    """Snapshot completo del player en formato wire (incluye ``liked``).

    Diferencias con :func:`_spotify_currently_playing_blocking`:

    * Siempre devuelve un dict (nunca tupla); el cliente decide cómo
      degradar cuando ``track`` es ``None``.
    * Resuelve ``liked`` consultando ``/me/tracks/contains`` si hay
      track activa (una sola request adicional, ~50 ms).
    * Incluye ``error`` con tipo + mensaje cuando algo falla, en vez
      de devolver una respuesta sin contexto.
    """
    try:
        payload = _spotify_get("/me/player")
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"available": False, "error": str(exc)}
    except requests.HTTPError as exc:
        return {
            "available": False,
            "error": f"spotify api error: {exc.response.status_code}",
        }

    if not payload:
        return {"available": True, "playing": False, "track": None}

    track = _spotify_track_dto(payload.get("item"))
    is_playing = bool(payload.get("is_playing"))
    liked: bool | None = None
    if track and track["uri"]:
        track_id = track["uri"].split(":")[-1]
        try:
            check = _spotify_get(
                "/me/tracks/contains", params={"ids": track_id},
            )
            if isinstance(check, list) and check:
                liked = bool(check[0])
        except (requests.HTTPError, spotify_auth.SpotifyAuthUnavailableError):
            # Best-effort — un fallo aquí no debe tumbar el snapshot.
            liked = None
    device = payload.get("device") or {}
    return {
        "available": True,
        "playing": is_playing,
        "track": track,
        "duration_ms": (track or {}).get("duration_ms", 0),
        "progress_ms": payload.get("progress_ms") or 0,
        "shuffle": bool(payload.get("shuffle_state")),
        "repeat_state": payload.get("repeat_state") or "off",
        "liked": liked,
        "device": {
            "id": device.get("id"),
            "name": device.get("name") or "",
            "type": (device.get("type") or "").lower(),
            "is_active": bool(device.get("is_active")),
            "volume_percent": device.get("volume_percent"),
            "supports_volume": bool(device.get("supports_volume")),
        } if device else None,
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
    uri: str | None, query: str | None, device_id: str | None = None,
) -> tuple[dict, dict | None]:
    """Start playback. Returns (response, scene_or_none).

    Si ``device_id`` está presente, ``PUT /me/player/play?device_id=X``
    transfiere la reproducción a ese device Y arranca — esencial tras
    despertar Spotify en el tablet, que aparece como dispositivo
    *disponible* pero no *activo*, así que un play sin device_id daría
    404 "no active device".
    """
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
        path = "/me/player/play"
        if device_id:
            path += f"?device_id={device_id}"
        try:
            _spotify_send("PUT", path, json_body=body)
        except requests.HTTPError as exc_inner:
            # Spotify devuelve 502/503 esporádico justo tras un cold
            # start del device (aparece en /me/player/devices pero su
            # backend interno aún no acepta play). Un único retry con
            # ~1s de pausa lo arregla en la mayoría de casos sin
            # alargar la respuesta cuando todo va bien.
            if device_id and exc_inner.response.status_code in (502, 503):
                log.info(
                    "spotify_play: %d on first attempt, retrying in 1.2s",
                    exc_inner.response.status_code,
                )
                time.sleep(1.2)
                _spotify_send("PUT", path, json_body=body)
            else:
                raise
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
# Tras lanzar Spotify en el tablet, cuánto esperamos (sondeando cada
# segundo) a que aparezca como dispositivo Connect. Un arranque en
# frío tarda fácil 5-10s en registrarse, así que el viejo delay fijo
# de 4s se quedaba corto.
_SPOTIFY_WAKE_POLL_TIMEOUT_S = 12.0
_SPOTIFY_WAKE_POLL_INTERVAL_S = 1.0


def _looks_like_no_active_device(response: dict[str, Any]) -> bool:
    err = str(response.get("error") or "").lower()
    return "no active" in err and "device" in err


def _wait_for_device_blocking(name_pattern: str) -> str | None:
    """Sondea /me/player/devices hasta que aparezca el tablet.

    Devuelve el device_id que matchea ``name_pattern`` (substring,
    case-insensitive), o el único device disponible si no hay match
    por nombre pero sí hay exactamente uno (caso típico: solo el
    tablet). None si tras el timeout no aparece nada.
    """
    deadline = time.monotonic() + _SPOTIFY_WAKE_POLL_TIMEOUT_S
    pattern = name_pattern.lower()
    while time.monotonic() < deadline:
        try:
            devices = _spotify_devices_blocking()
        except Exception:  # noqa: BLE001 — reintentamos en el siguiente tick
            devices = []
        match = next(
            (d for d in devices if pattern in (d.get("name") or "").lower()),
            None,
        )
        if match is None and len(devices) == 1:
            match = devices[0]
        if match is not None and match.get("id"):
            log.info(
                "spotify wake: device ready '%s' (%s)",
                match.get("name"), match.get("id"),
            )
            return match["id"]
        time.sleep(_SPOTIFY_WAKE_POLL_INTERVAL_S)
    return None


async def _spotify_play(
    uri: str | None = None,
    query: str | None = None,
    *,
    on_scene: SceneSink | None = None,
) -> ToolResult:
    """Reproduce algo en Spotify; si no hay device activo, "despierta"
    la app de Spotify en el tablet y reintenta apuntando a él.

    El flow auto-wake imita lo que hace Waze: si no hay nadie
    escuchando, le pedimos al tablet que lance la app de Spotify
    (Intent ``launchIntentForPackage``). Luego sondeamos
    /me/player/devices hasta que el tablet aparece como dispositivo
    Connect (cold start ~5-10s) y reproducimos con ``device_id``
    explícito — porque recién abierto Spotify está *disponible* pero
    no *activo*, así que un play sin device_id volvería a dar 404.

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

        from ..settings import settings as _settings
        pattern = _settings.kiwi_spotify_tablet_name or "tablet"
        device_id = await asyncio.to_thread(_wait_for_device_blocking, pattern)
        if device_id is not None:
            log.info("spotify_play: retrying targeting device %s", device_id)
            response, scene = await asyncio.to_thread(
                _spotify_play_blocking, uri, query, device_id,
            )
        else:
            log.info("spotify_play: tablet device never appeared after wake")

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


# ---- new playback ops: seek, shuffle, repeat, volume, like -------


def _spotify_seek_blocking(position_ms: int) -> dict:
    """Salta a ``position_ms`` en la pista actual."""
    try:
        _spotify_send("PUT", f"/me/player/seek?position_ms={position_ms}")
        return {"ok": True, "position_ms": position_ms}
    except SpotifyNoDeviceError:
        return {"error": "no active spotify device"}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


def _spotify_shuffle_blocking(enabled: bool) -> dict:
    """Activa/desactiva shuffle."""
    state = "true" if enabled else "false"
    try:
        _spotify_send("PUT", f"/me/player/shuffle?state={state}")
        return {"ok": True, "shuffle": enabled}
    except SpotifyNoDeviceError:
        return {"error": "no active spotify device"}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


_VALID_REPEAT_STATES = {"off", "context", "track"}


def _spotify_repeat_blocking(state: str) -> dict:
    """Pone repeat en ``off`` | ``context`` (playlist/álbum) | ``track``."""
    state = state.lower()
    if state not in _VALID_REPEAT_STATES:
        return {"error": f"invalid repeat state {state!r}"}
    try:
        _spotify_send("PUT", f"/me/player/repeat?state={state}")
        return {"ok": True, "repeat_state": state}
    except SpotifyNoDeviceError:
        return {"error": "no active spotify device"}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


def _spotify_volume_blocking(volume_percent: int) -> dict:
    """Ajusta el volumen del *device activo* (0-100)."""
    volume_percent = max(0, min(100, int(volume_percent)))
    try:
        _spotify_send("PUT", f"/me/player/volume?volume_percent={volume_percent}")
        return {"ok": True, "volume_percent": volume_percent}
    except SpotifyNoDeviceError:
        return {"error": "no active spotify device"}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


def _track_id_from_uri(uri: str) -> str | None:
    if not uri or not uri.startswith("spotify:track:"):
        return None
    return uri.split(":")[-1]


def _spotify_like_blocking(track_uri: str | None) -> dict:
    """Añade ``track_uri`` (o la pista activa) a Liked Songs."""
    if not track_uri:
        snapshot = _spotify_full_state_blocking()
        track_uri = ((snapshot.get("track") or {}).get("uri")) or ""
    track_id = _track_id_from_uri(track_uri)
    if not track_id:
        return {"error": "could not resolve a track uri to like"}
    try:
        _spotify_send("PUT", f"/me/tracks?ids={track_id}")
        return {"ok": True, "liked": True, "track_id": track_id}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


def _spotify_unlike_blocking(track_uri: str | None) -> dict:
    """Quita ``track_uri`` (o la pista activa) de Liked Songs."""
    if not track_uri:
        snapshot = _spotify_full_state_blocking()
        track_uri = ((snapshot.get("track") or {}).get("uri")) or ""
    track_id = _track_id_from_uri(track_uri)
    if not track_id:
        return {"error": "could not resolve a track uri to unlike"}
    try:
        _spotify_send("DELETE", f"/me/tracks?ids={track_id}")
        return {"ok": True, "liked": False, "track_id": track_id}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


# ---- queue --------------------------------------------------------


def _spotify_queue_blocking() -> dict:
    """Devuelve la cola actual: pista en curso + siguientes."""
    try:
        payload = _spotify_get("/me/player/queue")
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    current = _spotify_track_dto(payload.get("currently_playing"))
    queue = [
        _spotify_track_dto(t) or {} for t in payload.get("queue") or []
    ]
    return {
        "currently_playing": current,
        "queue": queue,
        "count": len(queue),
    }


def _spotify_add_to_queue_blocking(uri: str) -> dict:
    """Añade ``uri`` (track) al final de la cola del device activo."""
    if not uri or not uri.startswith("spotify:track:"):
        return {"error": f"invalid track uri {uri!r}"}
    try:
        _spotify_send("POST", f"/me/player/queue?uri={uri}")
        return {"ok": True, "queued": uri}
    except SpotifyNoDeviceError:
        return {"error": "no active spotify device"}
    except SpotifyPremiumRequiredError:
        return {"error": "spotify premium required"}
    except SpotifyRateLimitedError as exc:
        return {"error": "rate limited", "retry_after": exc.retry_after}
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}


# ---- library (read) -----------------------------------------------


def _playlist_summary_dto(p: dict) -> dict:
    return {
        "uri": p.get("uri"),
        "title": p.get("name") or "",
        "owner": (p.get("owner") or {}).get("display_name") or "",
        "album_art_url": _spotify_image_url(p.get("images")),
        "item_count": (p.get("tracks") or {}).get("total") or 0,
    }


def _album_summary_dto(a: dict) -> dict:
    return {
        "uri": a.get("uri"),
        "title": a.get("name") or "",
        "artist": ", ".join(
            x.get("name", "") for x in a.get("artists") or []
        ),
        "album_art_url": _spotify_image_url(a.get("images")),
    }


def _artist_summary_dto(a: dict) -> dict:
    return {
        "uri": a.get("uri"),
        "title": a.get("name") or "",
        "album_art_url": _spotify_image_url(a.get("images")),
        "genres": a.get("genres") or [],
    }


def _spotify_my_playlists_blocking(limit: int = 20) -> dict:
    limit = max(1, min(limit, 50))
    try:
        payload = _spotify_get("/me/playlists", params={"limit": limit})
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = [
        _playlist_summary_dto(p) for p in payload.get("items") or [] if p
    ]
    return {"count": len(items), "items": items}


def _spotify_recently_played_blocking(limit: int = 20) -> dict:
    limit = max(1, min(limit, 50))
    try:
        payload = _spotify_get(
            "/me/player/recently-played", params={"limit": limit},
        )
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = []
    seen_uris: set[str] = set()
    for entry in payload.get("items") or []:
        track = _spotify_track_dto(entry.get("track"))
        if not track:
            continue
        uri = track.get("uri") or ""
        if uri in seen_uris:  # dedup — Spotify repite tracks recientes
            continue
        seen_uris.add(uri)
        track["played_at"] = entry.get("played_at")
        items.append(track)
    return {"count": len(items), "items": items}


def _spotify_liked_songs_blocking(limit: int = 20, offset: int = 0) -> dict:
    limit = max(1, min(limit, 50))
    offset = max(0, offset)
    try:
        payload = _spotify_get(
            "/me/tracks", params={"limit": limit, "offset": offset},
        )
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = []
    for entry in payload.get("items") or []:
        track = _spotify_track_dto(entry.get("track"))
        if track:
            track["added_at"] = entry.get("added_at")
            items.append(track)
    return {
        "count": len(items),
        "total": payload.get("total") or len(items),
        "offset": offset,
        "items": items,
    }


def _spotify_featured_playlists_blocking(limit: int = 12) -> dict:
    """Playlists destacadas globalmente (Daily Mix, Discover Weekly se
    resuelven aquí cuando aparecen en la cuenta del usuario).

    Spotify deprecó en 2024 el endpoint ``/browse/featured-playlists``
    para apps nuevas — sigue funcionando para apps con consumo previo
    pero puede devolver 404. Si falla, intentamos un fallback a
    ``/me/playlists`` para no dejar la sección vacía.
    """
    limit = max(1, min(limit, 50))
    try:
        payload = _spotify_get(
            "/browse/featured-playlists",
            params={"limit": limit, "country": "ES"},
        )
        items = [
            _playlist_summary_dto(p)
            for p in ((payload.get("playlists") or {}).get("items") or [])
            if p
        ]
        if items:
            return {
                "count": len(items),
                "message": payload.get("message") or "",
                "items": items,
            }
    except requests.HTTPError as exc:
        if exc.response.status_code not in (403, 404):
            return {"error": f"spotify api error: {exc.response.status_code}"}
        # Fall through to fallback.
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    # Fallback: usa las playlists propias del usuario como sección
    # "Hechas para ti" pobre. No es lo mismo pero al menos no queda
    # vacío.
    return _spotify_my_playlists_blocking(limit=limit)


def _spotify_top_tracks_blocking(limit: int = 20) -> dict:
    limit = max(1, min(limit, 50))
    try:
        payload = _spotify_get(
            "/me/top/tracks",
            params={"limit": limit, "time_range": "medium_term"},
        )
    except requests.HTTPError as exc:
        if exc.response.status_code == 403:
            return {"error": "spotify scope missing: user-top-read"}
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = [
        _spotify_track_dto(t) or {} for t in payload.get("items") or []
    ]
    return {"count": len(items), "items": items}


def _spotify_top_artists_blocking(limit: int = 20) -> dict:
    limit = max(1, min(limit, 50))
    try:
        payload = _spotify_get(
            "/me/top/artists",
            params={"limit": limit, "time_range": "medium_term"},
        )
    except requests.HTTPError as exc:
        if exc.response.status_code == 403:
            return {"error": "spotify scope missing: user-top-read"}
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = [
        _artist_summary_dto(a) for a in payload.get("items") or []
    ]
    return {"count": len(items), "items": items}


def _spotify_playlist_tracks_blocking(playlist_uri: str, limit: int = 50) -> dict:
    """Lista los tracks de ``playlist_uri`` (acepta URI completa o ID)."""
    playlist_id = playlist_uri.split(":")[-1] if playlist_uri else ""
    if not playlist_id:
        return {"error": "missing playlist uri"}
    limit = max(1, min(limit, 100))
    try:
        meta = _spotify_get(f"/playlists/{playlist_id}")
        tracks_payload = _spotify_get(
            f"/playlists/{playlist_id}/tracks",
            params={"limit": limit, "market": "ES"},
        )
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}
    items = []
    for entry in tracks_payload.get("items") or []:
        track = _spotify_track_dto(entry.get("track"))
        if track:
            items.append(track)
    return {
        "uri": meta.get("uri"),
        "title": meta.get("name") or "",
        "owner": (meta.get("owner") or {}).get("display_name") or "",
        "album_art_url": _spotify_image_url(meta.get("images")),
        "count": len(items),
        "items": items,
    }


# ---- recommendations ---------------------------------------------


# Mapeo mood → target audio features. Valores derivados de las
# recommendations propias de Spotify; ver
# https://developer.spotify.com/documentation/web-api/reference/get-recommendations.
_MOOD_PROFILES: dict[str, dict[str, float]] = {
    "chill": {
        "target_energy": 0.35,
        "target_valence": 0.55,
        "target_acousticness": 0.6,
    },
    "energetic": {
        "target_energy": 0.85,
        "target_valence": 0.75,
        "target_danceability": 0.7,
    },
    "focus": {
        "target_energy": 0.40,
        "target_valence": 0.45,
        "target_instrumentalness": 0.6,
        "target_speechiness": 0.1,
    },
    "party": {
        "target_energy": 0.90,
        "target_valence": 0.80,
        "target_danceability": 0.85,
        "target_loudness": -5.0,
    },
    "sleep": {
        "target_energy": 0.20,
        "target_valence": 0.40,
        "target_acousticness": 0.85,
        "target_instrumentalness": 0.7,
    },
    "happy": {"target_energy": 0.70, "target_valence": 0.90},
    "sad": {
        "target_energy": 0.30,
        "target_valence": 0.20,
        "target_acousticness": 0.65,
    },
}

# Mapeo mood → seed_genre por defecto cuando no hay otra seed. Cada
# valor TIENE que aparecer en
# /recommendations/available-genre-seeds; los he validado contra el
# listado oficial. Sin esto, "Pon música chill" sin nada sonando fallaba
# porque el helper exigía al menos una semilla.
_MOOD_FALLBACK_GENRES: dict[str, str] = {
    "chill":     "chill",
    "energetic": "work-out",
    "focus":     "study",
    "party":     "party",
    "sleep":     "sleep",
    "happy":     "happy",
    "sad":       "sad",
}


def _spotify_recommend_blocking(
    seed_track: str | None = None,
    seed_artist: str | None = None,
    seed_genre: str | None = None,
    mood: str | None = None,
    limit: int = 20,
) -> dict:
    """Devuelve recomendaciones basadas en seed(s) + perfil de mood.

    Reglas:

    * Hay que pasar al menos UNA semilla (track / artist / genre).
    * Si ``mood`` está en :data:`_MOOD_PROFILES`, mezcla sus targets.
    * Devuelve también ``seed_summary`` para que el caller pueda
      mostrar qué semillas usó al usuario sin recalcularlas.
    """
    params: dict[str, Any] = {"limit": max(1, min(limit, 50)), "market": "ES"}
    seeds: list[str] = []
    if seed_track:
        sid = _track_id_from_uri(seed_track) or seed_track
        params["seed_tracks"] = sid
        seeds.append(f"track:{sid}")
    if seed_artist:
        aid = (
            seed_artist.split(":")[-1]
            if seed_artist.startswith("spotify:artist:")
            else seed_artist
        )
        params["seed_artists"] = aid
        seeds.append(f"artist:{aid}")
    if seed_genre:
        params["seed_genres"] = seed_genre
        seeds.append(f"genre:{seed_genre}")
    # Mood + no seed: usa el género default del mood como seed. Sin
    # esto, "pon música chill" sin nada sonando fallaba por
    # "supply at least one seed". El género default mapea 1:1 a
    # un seed_genre válido de Spotify.
    mood_key = mood.lower() if mood else None
    if not seeds and mood_key and mood_key in _MOOD_FALLBACK_GENRES:
        fallback = _MOOD_FALLBACK_GENRES[mood_key]
        params["seed_genres"] = fallback
        seeds.append(f"genre:{fallback}")
    if not seeds:
        return {"error": "supply at least one seed (track/artist/genre)"}
    if mood_key:
        profile = _MOOD_PROFILES.get(mood_key)
        if profile is None:
            return {
                "error": (
                    f"unknown mood {mood!r}. valid: "
                    f"{sorted(_MOOD_PROFILES.keys())}"
                ),
            }
        params.update(profile)

    try:
        payload = _spotify_get("/recommendations", params=params)
    except requests.HTTPError as exc:
        return {"error": f"spotify api error: {exc.response.status_code}"}
    except spotify_auth.SpotifyAuthUnavailableError as exc:
        return {"error": str(exc)}

    items = [
        _spotify_track_dto(t) or {} for t in payload.get("tracks") or []
    ]
    return {
        "count": len(items),
        "seeds": seeds,
        "mood": mood,
        "items": items,
    }


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
    from ..settings import settings as _settings

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

    from .. import state_store

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


# ---- voice tools: shuffle / repeat / seek / like / queue / library


async def _spotify_shuffle_tool(enabled: bool) -> dict:
    return await asyncio.to_thread(_spotify_shuffle_blocking, enabled)


async def _spotify_repeat_tool(mode: str) -> dict:
    return await asyncio.to_thread(_spotify_repeat_blocking, mode)


async def _spotify_seek_tool(position_ms: int) -> dict:
    return await asyncio.to_thread(
        _spotify_seek_blocking, max(0, int(position_ms)),
    )


async def _spotify_like_tool() -> dict:
    return await asyncio.to_thread(_spotify_like_blocking, None)


async def _spotify_unlike_tool() -> dict:
    return await asyncio.to_thread(_spotify_unlike_blocking, None)


async def _spotify_add_to_queue_tool(
    uri: str | None = None, query: str | None = None,
) -> dict:
    target_uri = uri
    if not target_uri and query:
        match = await asyncio.to_thread(_try_resolve_first_track, query)
        if not match:
            return {"error": f"no spotify track found for {query!r}"}
        target_uri = match.get("uri")
    if not target_uri:
        return {"error": "supply either uri or query"}
    return await asyncio.to_thread(_spotify_add_to_queue_blocking, target_uri)


async def _spotify_view_queue_tool() -> dict:
    return await asyncio.to_thread(_spotify_queue_blocking)


async def _spotify_my_playlists_tool(max_results: int = 20) -> ToolResult:
    result = await asyncio.to_thread(_spotify_my_playlists_blocking, max_results)
    scene = _spotify_results_scene("playlist", "Tus playlists", result)
    return ToolResult(response=result, scene=scene)


async def _spotify_recently_played_tool(max_results: int = 20) -> ToolResult:
    result = await asyncio.to_thread(_spotify_recently_played_blocking, max_results)
    scene = _spotify_results_scene("track", "Escuchado recientemente", result)
    return ToolResult(response=result, scene=scene)


async def _spotify_liked_songs_tool(max_results: int = 30) -> ToolResult:
    result = await asyncio.to_thread(
        _spotify_liked_songs_blocking, max_results, 0,
    )
    scene = _spotify_results_scene("track", "Tus canciones favoritas", result)
    return ToolResult(response=result, scene=scene)


async def _spotify_featured_playlists_tool(max_results: int = 12) -> ToolResult:
    result = await asyncio.to_thread(
        _spotify_featured_playlists_blocking, max_results,
    )
    scene = _spotify_results_scene(
        "playlist", result.get("message") or "Hechas para ti", result,
    )
    return ToolResult(response=result, scene=scene)


async def _spotify_top_tracks_tool(max_results: int = 20) -> ToolResult:
    result = await asyncio.to_thread(_spotify_top_tracks_blocking, max_results)
    scene = _spotify_results_scene("track", "Te encantan", result)
    return ToolResult(response=result, scene=scene)


async def _spotify_top_artists_tool(max_results: int = 20) -> ToolResult:
    result = await asyncio.to_thread(_spotify_top_artists_blocking, max_results)
    scene = _spotify_results_scene("artist", "Tus artistas", result)
    return ToolResult(response=result, scene=scene)


async def _spotify_recommend_tool(
    seed_track: str | None = None,
    seed_artist: str | None = None,
    seed_genre: str | None = None,
    mood: str | None = None,
    max_results: int = 20,
) -> ToolResult:
    # Sin semilla pero con mood "chill"/"focus"/etc: usa la pista en
    # curso como track-seed si la hay. Si no hay nada activo y nada
    # explícito, seed_genre por defecto al primer género del top artist.
    if not seed_track and not seed_artist and not seed_genre:
        snapshot = await asyncio.to_thread(_spotify_full_state_blocking)
        active_uri = ((snapshot.get("track") or {}).get("uri")) or ""
        if active_uri.startswith("spotify:track:"):
            seed_track = active_uri
    result = await asyncio.to_thread(
        _spotify_recommend_blocking,
        seed_track, seed_artist, seed_genre, mood, max_results,
    )
    if "error" in result:
        return ToolResult(response=result)
    label = (
        f"Recomendaciones · {mood}" if mood else "Recomendaciones"
    )
    scene = _spotify_results_scene("track", label, result)
    return ToolResult(response=result, scene=scene)


async def _spotify_play_mix_tool(name: str) -> ToolResult:
    """Resuelve fuzzy ``name`` contra las playlists del usuario + las
    "Made for You" / featured y reproduce la primera que case.

    Útil para "Kiwi, pon mi Daily Mix 2" sin que el usuario tenga
    que recordar la URI exacta. Si nada casa, devuelve error con
    la lista de mixes disponibles.
    """
    if not name or not name.strip():
        return ToolResult(response={"error": "missing mix name"})
    mine = await asyncio.to_thread(_spotify_my_playlists_blocking, 50)
    featured = await asyncio.to_thread(_spotify_featured_playlists_blocking, 50)
    candidates: list[dict] = []
    candidates.extend(mine.get("items") or [])
    for p in featured.get("items") or []:
        if not any(c.get("uri") == p.get("uri") for c in candidates):
            candidates.append(p)
    if not candidates:
        return ToolResult(response={"error": "no playlists found"})
    from .. import state_store
    matched = state_store.fuzzy_match_one(
        candidates, name, key=lambda p: p.get("title") or "",
    )
    if matched is None:
        titles = [p.get("title") or "?" for p in candidates[:10]]
        return ToolResult(response={
            "error": f"no mix matched {name!r}. available: {titles}",
        })
    response, scene = await asyncio.to_thread(
        _spotify_play_blocking, matched.get("uri"), None, None,
    )
    response = {**response, "mix_title": matched.get("title")}
    return ToolResult(response=response, scene=scene)


def _spotify_results_scene(
    kind: str, title: str, payload: dict[str, Any],
) -> dict | None:
    """Construye una scene ``spotify_results`` con los items.

    Devuelve ``None`` si el payload trae ``error`` o no hay items —
    el caller decide cómo informar (típicamente: voice-only).
    """
    if "error" in payload:
        return None
    items = payload.get("items") or []
    if not items:
        return None
    return {
        "type": "spotify_results",
        "kind": kind,
        "title": title,
        "count": len(items),
        "items": items,
    }


register(
    name="spotify_shuffle",
    description=(
        "Activa o desactiva el modo aleatorio en Spotify. Llama "
        "cuando el usuario diga 'aleatorio sí/no', 'shuffle on/off', "
        "'mezcla esto', 'sin mezclar'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "enabled": types.Schema(
                type=types.Type.BOOLEAN,
                description="true para activar shuffle, false para desactivarlo.",
            ),
        },
        required=["enabled"],
    ),
    handler=_spotify_shuffle_tool,
)


register(
    name="spotify_repeat",
    description=(
        "Configura el modo de repetición de Spotify: 'off' (sin repe), "
        "'context' (repite playlist/álbum) o 'track' (repite la canción "
        "actual). Llama cuando el usuario diga 'repite esto / esta', "
        "'pon en bucle', 'no repitas', etc."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "mode": types.Schema(
                type=types.Type.STRING,
                description="'off' / 'context' / 'track'.",
            ),
        },
        required=["mode"],
    ),
    handler=_spotify_repeat_tool,
)


register(
    name="spotify_seek",
    description=(
        "Salta a una posición concreta de la pista actual. Llama "
        "cuando el usuario diga 'salta al minuto 2', 'vuelve al "
        "principio', 'al final', etc. ``position_ms`` es absoluto "
        "desde el inicio (1 minuto = 60000)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "position_ms": types.Schema(
                type=types.Type.INTEGER,
                description="Posición absoluta en milisegundos.",
            ),
        },
        required=["position_ms"],
    ),
    handler=_spotify_seek_tool,
)


register(
    name="spotify_like",
    description=(
        "Añade la canción que suena ahora a 'Canciones que te gustan'. "
        "Llama cuando el usuario diga 'me gusta esta', 'guárdala', "
        "'añádela a favoritos', 'corazón a esta'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_like_tool,
)


register(
    name="spotify_unlike",
    description=(
        "Quita la canción que suena ahora de 'Canciones que te gustan'. "
        "Llama cuando el usuario diga 'quítala de favoritos', 'ya no "
        "me gusta', 'no era favorita'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_unlike_tool,
)


register(
    name="spotify_add_to_queue",
    description=(
        "Añade una canción al final de la cola de Spotify. Pasa 'uri' "
        "(spotify:track:...) cuando lo tengas de un spotify_search, o "
        "'query' (texto libre) si el usuario describe la canción. "
        "Llama cuando diga 'encola X', 'añade a la cola Y', 'pon "
        "después esta'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "uri": types.Schema(
                type=types.Type.STRING,
                description="URI de track (spotify:track:...).",
            ),
            "query": types.Schema(
                type=types.Type.STRING,
                description="Texto libre para resolver la pista a encolar.",
            ),
        },
    ),
    handler=_spotify_add_to_queue_tool,
)


register(
    name="spotify_view_queue",
    description=(
        "Devuelve la cola actual de Spotify (pista en curso + las "
        "siguientes). Útil cuando el usuario diga '¿qué viene "
        "después?', 'cola', 'qué hay en la cola'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_spotify_view_queue_tool,
)


register(
    name="spotify_my_playlists",
    description=(
        "Lista las playlists del usuario en Spotify (las suyas + las "
        "que sigue). Empuja la lista a la pantalla. Llama cuando el "
        "usuario diga 'mis playlists', 'lista de listas', 'enséñame "
        "mis listas'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de playlists (1-50, por defecto 20).",
            ),
        },
    ),
    handler=_spotify_my_playlists_tool,
)


register(
    name="spotify_recently_played",
    description=(
        "Lista las pistas escuchadas recientemente (deduplicadas). "
        "Llama cuando el usuario diga 'qué he escuchado', 'reciente', "
        "'cosas que sonaron ayer'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de pistas (1-50, por defecto 20).",
            ),
        },
    ),
    handler=_spotify_recently_played_tool,
)


register(
    name="spotify_liked_songs",
    description=(
        "Lista las pistas marcadas como favoritas ('Canciones que te "
        "gustan'). Llama cuando el usuario diga 'mis favoritas', 'mis "
        "likes', 'mis canciones guardadas'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo de pistas (1-50, por defecto 30).",
            ),
        },
    ),
    handler=_spotify_liked_songs_tool,
)


register(
    name="spotify_featured_playlists",
    description=(
        "Playlists destacadas y curadas (Daily Mix, Discover Weekly y "
        "similares). Llama cuando el usuario diga 'hechas para mí', "
        "'sugerencias', 'recomendado'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo (1-50, por defecto 12).",
            ),
        },
    ),
    handler=_spotify_featured_playlists_tool,
)


register(
    name="spotify_top_tracks",
    description=(
        "Las pistas más escuchadas del usuario (últimos ~6 meses). "
        "Llama cuando diga 'lo que más escucho', 'top canciones', "
        "'mis tops'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo (1-50, por defecto 20).",
            ),
        },
    ),
    handler=_spotify_top_tracks_tool,
)


register(
    name="spotify_top_artists",
    description=(
        "Los artistas más escuchados del usuario (últimos ~6 meses). "
        "Llama cuando diga 'mis artistas favoritos', 'top artistas', "
        "'a quién escucho más'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo (1-50, por defecto 20).",
            ),
        },
    ),
    handler=_spotify_top_artists_tool,
)


register(
    name="spotify_recommend",
    description=(
        "Genera recomendaciones musicales basadas en semillas y/o un "
        "estado de ánimo. Útil para 'pon música chill', 'algo "
        "parecido a esto' (sin seed usa la pista activa como track), "
        "'recomiéndame algo de Aitana' (con seed_artist), 'pon flamenco' "
        "(con seed_genre). Moods válidos: chill, energetic, focus, "
        "party, sleep, happy, sad."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "seed_track": types.Schema(
                type=types.Type.STRING,
                description="URI o ID de track de partida.",
            ),
            "seed_artist": types.Schema(
                type=types.Type.STRING,
                description="URI o ID de artista de partida.",
            ),
            "seed_genre": types.Schema(
                type=types.Type.STRING,
                description="Género: 'pop' / 'flamenco' / 'electronic' …",
            ),
            "mood": types.Schema(
                type=types.Type.STRING,
                description="chill / energetic / focus / party / sleep / happy / sad.",
            ),
            "max_results": types.Schema(
                type=types.Type.INTEGER,
                description="Máximo (1-50, por defecto 20).",
            ),
        },
    ),
    handler=_spotify_recommend_tool,
)


register(
    name="spotify_play_mix",
    description=(
        "Reproduce una playlist resuelta por nombre (fuzzy match contra "
        "tus playlists + las 'Hechas para ti'). Llama cuando el "
        "usuario diga 'pon mi Daily Mix 2', 'pon la de Discover "
        "Weekly', 'reproduce la lista de chill'."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "name": types.Schema(
                type=types.Type.STRING,
                description="Nombre aproximado del mix / playlist.",
            ),
        },
        required=["name"],
    ),
    handler=_spotify_play_mix_tool,
)


# ---- media control unificado (Spotify ↔ YouTube ↔ otros) -------


async def _media_pause_tool() -> ToolResult:
    """Pausa lo que esté sonando. Preferencia: Spotify si está playing,
    si no, manda media key al device (cubre YouTube y cualquier app
    con MediaSession activa)."""
    snapshot = await asyncio.to_thread(_spotify_full_state_blocking)
    if snapshot.get("available") and snapshot.get("playing"):
        return ToolResult(response=await _spotify_pause())
    # Fallback: pulsar el media_key del SO via el tablet. El cliente
    # Android implementa "media_key" → MediaSessionManager.dispatch…
    return ToolResult(
        response={"ok": True, "via": "media_key"},
        scene={"type": "device_command", "command": "media_key", "label": "pause"},
    )


async def _media_resume_tool() -> ToolResult:
    snapshot = await asyncio.to_thread(_spotify_full_state_blocking)
    if snapshot.get("available") and snapshot.get("track") is not None:
        return ToolResult(response=await _spotify_resume())
    return ToolResult(
        response={"ok": True, "via": "media_key"},
        scene={"type": "device_command", "command": "media_key", "label": "play"},
    )


async def _media_next_tool() -> ToolResult:
    snapshot = await asyncio.to_thread(_spotify_full_state_blocking)
    if snapshot.get("available") and snapshot.get("playing"):
        return ToolResult(response=await _spotify_next())
    return ToolResult(
        response={"ok": True, "via": "media_key"},
        scene={"type": "device_command", "command": "media_key", "label": "next"},
    )


async def _media_previous_tool() -> ToolResult:
    snapshot = await asyncio.to_thread(_spotify_full_state_blocking)
    if snapshot.get("available") and snapshot.get("playing"):
        return ToolResult(response=await _spotify_previous())
    return ToolResult(
        response={"ok": True, "via": "media_key"},
        scene={"type": "device_command", "command": "media_key", "label": "previous"},
    )


register(
    name="media_pause",
    description=(
        "Pausa lo que esté sonando (Spotify, YouTube, podcasts, etc.). "
        "Prefiere Spotify si hay reproducción activa allí; si no, "
        "manda la tecla 'pausa' al sistema."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_media_pause_tool,
)


register(
    name="media_resume",
    description=(
        "Reanuda la reproducción pausada en cualquier app de medios. "
        "Prefiere Spotify si hay sesión Spotify cargada."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_media_resume_tool,
)


register(
    name="media_next",
    description=(
        "Salta al siguiente contenido (canción/episodio/video). "
        "Prefiere Spotify si hay reproducción activa; si no, manda la "
        "tecla 'siguiente' al sistema."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_media_next_tool,
)


register(
    name="media_previous",
    description=(
        "Vuelve al contenido anterior. Prefiere Spotify si hay "
        "reproducción activa; si no, manda la tecla 'anterior'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_media_previous_tool,
)
