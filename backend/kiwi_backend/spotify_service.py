"""Service layer for Spotify, sitting between HTTP/SSE handlers and
the low-level helpers in ``tools.spotify``.

Pourquoi:

* Los endpoints REST (``main.py:/api/spotify/*``) y el state-pump SSE
  (``spotify_stream.py``) necesitan una API estable que no esté
  acoplada al sistema de tools de Gemini.
* Las voice tools (``tools.spotify._spotify_*_tool``) ya devuelven
  envueltos en :class:`ToolResult` con scene; las HTTP responses no
  quieren ``ToolResult`` ni scene push.
* Aquí también centralizamos detección de "Premium Required" para
  devolver 403 en vez de 502, y "rate limited" para 429 con el
  ``Retry-After`` propagado.

Diseño: cada función pública es **async**, hace ``asyncio.to_thread``
sobre el helper blocking, y devuelve un dict listo para serializar a
JSON. Errores se devuelven como ``{"error": "...", ...}`` con código
HTTP sugerido en la clave ``status`` opcional (default 502) — los
endpoints HTTP lo traducen a ``HTTPException``.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

import requests

from . import spotify_auth, state_store
from .tools import spotify as t

log = logging.getLogger(__name__)


def _err(message: str, status: int = 502, **extra: Any) -> dict[str, Any]:
    out: dict[str, Any] = {"error": message, "status": status}
    if extra:
        out.update(extra)
    return out


def _classify(exc: Exception) -> dict[str, Any]:
    """Map a known Spotify exception to a JSON error dict + HTTP status."""
    if isinstance(exc, t.SpotifyNoDeviceError):
        return _err(
            "no active spotify device. Abre Spotify en algún device para activarlo.",
            status=409,
        )
    if isinstance(exc, t.SpotifyPremiumRequiredError):
        return _err("spotify premium required", status=402)
    if isinstance(exc, t.SpotifyRateLimitedError):
        return _err(
            f"spotify rate limited; retry after {exc.retry_after}s",
            status=429, retry_after=exc.retry_after,
        )
    if isinstance(exc, spotify_auth.SpotifyAuthUnavailableError):
        return _err(str(exc), status=503)
    if isinstance(exc, requests.HTTPError):
        return _err(
            f"spotify api error: {exc.response.status_code}",
            status=502, spotify_status=exc.response.status_code,
        )
    return _err(f"{type(exc).__name__}: {exc}", status=500)


# ---- read --------------------------------------------------------


async def current_state() -> dict[str, Any]:
    """Full player snapshot: track + state + device + liked + shuffle/repeat.

    Devuelve ``{"available": False, "error": "..."}`` si no hay creds o
    Spotify rechaza la request — never raises.
    """
    snapshot = await asyncio.to_thread(t._spotify_full_state_blocking)
    return snapshot


async def list_devices() -> dict[str, Any]:
    try:
        devices = await asyncio.to_thread(t._spotify_devices_blocking)
    except Exception as exc:  # noqa: BLE001
        return _classify(exc)
    return {
        "devices": [
            {
                "id": d.get("id"),
                "name": d.get("name") or "",
                "type": (d.get("type") or "").lower(),
                "is_active": bool(d.get("is_active")),
                "is_restricted": bool(d.get("is_restricted")),
                "volume_percent": d.get("volume_percent"),
                "supports_volume": bool(d.get("supports_volume")),
            }
            for d in devices
        ],
    }


async def search(query: str, kind: str = "track", limit: int = 20) -> dict[str, Any]:
    if not query or not query.strip():
        return _err("missing query", status=400)
    kind = kind.lower()
    if kind not in t._VALID_SEARCH_TYPES:
        return _err(
            f"unknown kind {kind!r}. valid: {sorted(t._VALID_SEARCH_TYPES)}",
            status=400,
        )
    try:
        return await asyncio.to_thread(t._spotify_search_blocking, query, kind, limit)
    except Exception as exc:  # noqa: BLE001
        return _classify(exc)


# ---- write: playback control ------------------------------------


async def play(uri: str | None = None, query: str | None = None) -> dict[str, Any]:
    response, _scene = await asyncio.to_thread(
        t._spotify_play_blocking, uri, query, None,
    )
    if "error" in response:
        return _err(response["error"], status=409 if "device" in response["error"] else 502)
    return response


async def pause() -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_simple_command, "PUT", "/me/player/pause",
    )


async def resume() -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_simple_command, "PUT", "/me/player/play",
    )


async def next_track() -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_simple_command, "POST", "/me/player/next",
    )


async def previous_track() -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_simple_command, "POST", "/me/player/previous",
    )


async def seek(position_ms: int) -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_seek_blocking, max(0, int(position_ms)),
    )


async def shuffle(enabled: bool) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_shuffle_blocking, enabled)


async def repeat(mode: str) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_repeat_blocking, mode)


async def set_volume(volume_percent: int) -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_volume_blocking, max(0, min(100, int(volume_percent))),
    )


async def transfer(device_id: str, *, play: bool = True) -> dict[str, Any]:
    if not device_id:
        return _err("missing device_id", status=400)
    response, scene = await asyncio.to_thread(
        t._spotify_transfer_blocking, device_id,
    )
    if "error" in response:
        return _err(response["error"], status=409 if "device" in response["error"] else 502)
    return {**response, "scene": scene}


async def transfer_to_name(target: str) -> dict[str, Any]:
    """Fuzzy-match ``target`` against device names and transfer."""
    if not target or not target.strip():
        return _err("missing target", status=400)
    try:
        devices = await asyncio.to_thread(t._spotify_devices_blocking)
    except Exception as exc:  # noqa: BLE001
        return _classify(exc)
    matched = state_store.fuzzy_match_one(
        list(devices), target, key=lambda d: d.get("name") or "",
    )
    if matched is None:
        names = [d.get("name") or "?" for d in devices]
        return _err(
            f"no device matched {target!r}. available: {names}",
            status=404,
        )
    response, _scene = await asyncio.to_thread(
        t._spotify_transfer_blocking, matched["id"],
    )
    return {**response, "device_name": matched.get("name")}


# ---- write: library -------------------------------------------


async def like(track_uri: str | None = None) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_like_blocking, track_uri)


async def unlike(track_uri: str | None = None) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_unlike_blocking, track_uri)


async def add_to_queue(uri: str | None = None, query: str | None = None) -> dict[str, Any]:
    target_uri = uri
    if not target_uri and query:
        match = await asyncio.to_thread(t._try_resolve_first_track, query)
        if not match:
            return _err(f"no track found for {query!r}", status=404)
        target_uri = match.get("uri")
    if not target_uri:
        return _err("supply either uri or query", status=400)
    return await asyncio.to_thread(t._spotify_add_to_queue_blocking, target_uri)


async def queue() -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_queue_blocking)


# ---- read: library --------------------------------------------


async def my_playlists(limit: int = 20) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_my_playlists_blocking, limit)


async def recently_played(limit: int = 20) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_recently_played_blocking, limit)


async def liked_songs(limit: int = 30, offset: int = 0) -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_liked_songs_blocking, limit, offset,
    )


async def featured_playlists(limit: int = 12) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_featured_playlists_blocking, limit)


async def top_tracks(limit: int = 20) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_top_tracks_blocking, limit)


async def top_artists(limit: int = 20) -> dict[str, Any]:
    return await asyncio.to_thread(t._spotify_top_artists_blocking, limit)


async def playlist_tracks(playlist_uri: str, limit: int = 50) -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_playlist_tracks_blocking, playlist_uri, limit,
    )


# ---- recommend ------------------------------------------------


async def recommend(
    seed_track: str | None = None,
    seed_artist: str | None = None,
    seed_genre: str | None = None,
    mood: str | None = None,
    limit: int = 20,
) -> dict[str, Any]:
    return await asyncio.to_thread(
        t._spotify_recommend_blocking,
        seed_track, seed_artist, seed_genre, mood, limit,
    )
