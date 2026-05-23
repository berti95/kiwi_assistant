"""Tools relacionadas con YouTube (search, playlists, reproducción)."""
from __future__ import annotations

import asyncio
import logging
import re
import unicodedata
from typing import Any

from google.genai import types
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from .. import google_auth
from .registry import ToolResult, register

log = logging.getLogger(__name__)

# Caché de la última lista de vídeos que se le mostró al usuario
# (resultados de búsqueda o ítems de playlist). youtube_play resuelve
# una POSICIÓN sobre esto para sacar el video_id real: Gemini es
# malísimo copiando los 11 chars del id verbatim (los alucina) pero
# fiable diciendo "el segundo". Single-user / single-instance, así
# que un global basta; la última lista gana.
_last_video_results: list[dict[str, Any]] = []


def _remember_results(videos: list[dict[str, Any]]) -> list[dict[str, Any]]:
    global _last_video_results
    _last_video_results = list(videos)
    return videos

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
    _remember_results(videos)
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
    _remember_results(videos)
    return ToolResult(
        response={
            "playlist_id": playlist_id,
            "title": title,
            "count": len(videos),
            "videos": videos,
        },
        scene={"type": "video_list", "title": title, "videos": videos},
    )


def _youtube_watch_later() -> ToolResult:
    """Abre la lista "Ver más tarde" REAL en la app de YouTube.

    Antes leíamos una playlist custom como stand-in porque la Data
    API no expone la Watch Later del sistema (``WL``) desde 2016 —
    pero eso solo enseñaba una lista falsa en Kiwi. Con el deep link
    a ``playlist?list=WL`` la app nativa abre tu Watch Later de
    verdad, con tu cuenta. El stand-in por API queda obsoleto.
    """
    return ToolResult(
        response={"opened": "watch_later"},
        scene=_yt_deeplink("https://www.youtube.com/playlist?list=WL"),
    )


def _youtube_open(url: str | None = None) -> ToolResult:
    """Abre YouTube en la app nativa (deep link).

    Fallback genérico para lo que no tiene tool dedicada: home,
    suscripciones, un canal concreto, una playlist, una búsqueda…
    Gemini construye la URL de youtube.com adecuada y la abrimos en
    la app, donde el usuario ya está con su cuenta (a diferencia del
    WebView, que requería login y lo personalizado no funcionaba).
    """
    target = (url or "https://www.youtube.com").strip()
    if not target.startswith(("http://", "https://")):
        target = "https://" + target
    return ToolResult(
        response={"opened": target},
        scene=_yt_deeplink(target),
    )


# Paquete de la app de YouTube en Android. El tablet hace deep-link
# aquí en vez de embeber en un WebView: la app nativa reproduce con
# la cuenta del usuario (Premium, historial, ver más tarde) y no
# sufre el bloqueo de embed (error 152-4) que YouTube aplica a los
# iframes en WebView desde finales de 2025.
_YT_PACKAGE = "com.google.android.youtube"


def _yt_deeplink(url: str) -> dict[str, Any]:
    """device_command que el tablet resuelve abriendo la app de YouTube."""
    return {
        "type": "device_command",
        "command": "open_app_url",
        "package": _YT_PACKAGE,
        "url": url,
    }


def _ui_click(label: str = "") -> ToolResult:
    """Pulsa por voz un botón de la app que está en primer plano (YouTube).

    Emite un device_command que el tablet resuelve con su
    AccessibilityService: busca en pantalla el botón cuya etiqueta
    coincide con ``label`` (sin acentos, exacto/prefijo/substring) y lo
    pulsa. No abre nada ni cambia de pantalla — el usuario sigue viendo
    el vídeo. Si el servicio de accesibilidad no está habilitado en el
    tablet, la acción se ignora ahí (Kiwi no se entera; confirma de
    forma genérica).
    """
    label = (label or "").strip()
    if not label:
        return ToolResult(response={"error": "missing label"})
    return ToolResult(
        response={"clicked": label},
        scene={"type": "device_command", "command": "ui_click", "label": label},
    )


def _youtube_play(
    position: int = 0,
    video_id: str = "",
    title: str = "",
    channel: str = "",
) -> ToolResult:
    """Abre un vídeo en la app de YouTube (deep link).

    Resolución del vídeo, en orden de preferencia:
      1. ``position`` (1-based) sobre la última lista mostrada
         (search / playlist). ESTE es el camino fiable: Gemini dice
         "el segundo" y el backend saca el video_id REAL de la caché.
      2. ``video_id`` directo — solo como último recurso, porque
         Gemini alucina los 11 chars del id y manda uno inexistente
         → "vídeo no disponible" en la app. Si hay caché y el id no
         está en ella, lo tratamos como sospechoso y, si hay
         posición implícita imposible, devolvemos error pidiendo
         posición.

    Lanzamos la app nativa en ``watch?v=ID`` — reproduce con la
    cuenta del usuario y su Premium, sin el bloqueo de embed (152-4).
    """
    vid = ""
    if position and _last_video_results:
        idx = int(position) - 1
        if 0 <= idx < len(_last_video_results):
            item = _last_video_results[idx]
            vid = (item.get("video_id") or "").strip()
            title = title or item.get("title", "")
            channel = channel or item.get("channel", "")
        else:
            return ToolResult(response={
                "error": (
                    f"position {position} fuera de rango "
                    f"(hay {len(_last_video_results)} vídeos en la lista)"
                ),
            })
    elif video_id and video_id.strip():
        vid = video_id.strip()
        # Si tenemos una lista cacheada y el id no está en ella, es
        # casi seguro una alucinación de Gemini → pedir posición.
        if _last_video_results and not any(
            (v.get("video_id") or "") == vid for v in _last_video_results
        ):
            return ToolResult(response={
                "error": (
                    "ese video_id no está en la última lista mostrada — "
                    "probablemente inventado. Llama de nuevo con "
                    "'position' (1, 2, 3…) según el orden de la lista."
                ),
            })

    if not vid:
        return ToolResult(response={
            "error": "indica 'position' (sobre la última lista) o 'video_id'",
        })
    return ToolResult(
        response={"playing": vid, "title": title, "channel": channel},
        scene=_yt_deeplink(f"https://www.youtube.com/watch?v={vid}"),
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
        "Abre la lista 'Ver más tarde' del usuario en la app de YouTube. "
        "Llama a este tool SIEMPRE que el usuario diga 'ver más tarde', "
        "'pendientes', 'videos guardados', 'lo que tengo para ver', o "
        "variantes. Abre la Watch Later REAL de su cuenta en la app "
        "nativa (con su Premium); no devuelve una lista en pantalla, "
        "así que tras llamarlo confirma brevemente ('Te abro tu lista "
        "de ver más tarde')."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_youtube_watch_later,
)

register(
    name="youtube_open",
    description=(
        "Abre YouTube en la app nativa, en la sección que pidas. Llama a "
        "este tool SOLO cuando no haya una tool específica: suscripciones "
        "(https://www.youtube.com/feed/subscriptions), home, un canal "
        "(https://www.youtube.com/@handle), tendencias, etc. Construye la "
        "URL de youtube.com adecuada. Para una playlist concreta usa "
        "youtube_playlist_items; para 'ver más tarde' usa "
        "youtube_watch_later; para buscar usa youtube_search; para "
        "reproducir un vídeo usa youtube_play. Se abre en la app con la "
        "cuenta del usuario, así que tras llamarlo confirma brevemente."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "url": types.Schema(
                type=types.Type.STRING,
                description=(
                    "URL de youtube.com a abrir (p.ej. "
                    "https://www.youtube.com/feed/subscriptions). Por "
                    "defecto la home."
                ),
            ),
        },
    ),
    handler=_youtube_open,
)

register(
    name="ui_click",
    description=(
        "Pulsa un botón VISIBLE de la app de YouTube que el usuario está "
        "viendo, buscándolo por su etiqueta en pantalla. Úsalo para "
        "acciones que no tienen tool propia: dar 'Me gusta' (label='Me "
        "gusta'), saltar un anuncio (label='Saltar anuncio'), "
        "suscribirse (label='Suscribirse'), compartir, guardar, etc. "
        "Traduce lo que pide el usuario a la ETIQUETA del botón tal y "
        "como aparece en YouTube en español ('dale like' → 'Me gusta'; "
        "'quita el anuncio' → 'Saltar anuncio'; 'sígueme este canal' → "
        "'Suscribirse'). El tablet hace match sin acentos y por "
        "substring, así que no hace falta clavar mayúsculas. El botón "
        "debe estar en pantalla (en pantalla completa muchos no se ven). "
        "No abre ni cambia nada; el usuario sigue en el vídeo. Tras "
        "llamarlo confirma muy breve ('Hecho')."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta del botón a pulsar, tal y como sale en "
                    "YouTube (p.ej. 'Me gusta', 'Saltar anuncio', "
                    "'Suscribirse')."
                ),
            ),
        },
        required=["label"],
    ),
    handler=_ui_click,
)

register(
    name="youtube_play",
    description=(
        "Reproduce un vídeo de la última lista (búsqueda o playlist) "
        "abriéndolo en la app nativa de YouTube (con la cuenta y Premium "
        "del usuario).\n"
        "IMPORTANTE: usa SIEMPRE 'position' — el número de orden del "
        "vídeo en la última lista que mostraste (1 = el primero, 2 = el "
        "segundo…). Cuando el usuario diga 'pon el segundo', 'el de "
        "Coldplay' (que era el tercero), etc., traduce a su posición. "
        "NO inventes ni copies el video_id: son 11 caracteres aleatorios "
        "que se corrompen al copiarlos y entonces YouTube dice 'no "
        "disponible'. Solo usa 'video_id' si no hay lista previa y "
        "tienes un id verificado de esta misma conversación.\n"
        "Tras llamarlo, confirma brevemente ('Te lo pongo')."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "position": types.Schema(
                type=types.Type.INTEGER,
                description=(
                    "Posición 1-based del vídeo en la última lista "
                    "mostrada (búsqueda / playlist). El camino preferido."
                ),
            ),
            "video_id": types.Schema(
                type=types.Type.STRING,
                description=(
                    "ID de YouTube (11 chars). Solo como último recurso; "
                    "prefiere 'position'."
                ),
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
    ),
    handler=_youtube_play,
)
