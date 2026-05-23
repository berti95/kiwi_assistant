"""Tools de Spotify: estado, búsqueda, control y transferencia entre devices."""
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
        _spotify_send("PUT", path, json_body=body)
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
