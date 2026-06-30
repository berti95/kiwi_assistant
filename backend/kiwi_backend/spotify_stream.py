"""Server-Sent Events fan-out for Spotify player state.

Un único *state pump* corre en el event loop principal del backend
(arrancado lazy la primera vez que un cliente se conecta a
``/api/spotify/stream``). Hace polling a ``/me/player`` cada
:data:`POLL_INTERVAL_S` segundos, compara con el snapshot previo y
emite eventos a todas las colas de los clientes conectados.

Detalles:

* El primer evento que un cliente recibe es un snapshot completo
  ``{"event": "snapshot", "state": {...}}``. A partir de ahí, eventos
  ``{"event": "update", "state": {...}}`` con el snapshot fresco
  cuando cambia algo. Mantenemos snapshot completo (no diffs JSON
  Patch) — el payload es pequeño y la implementación queda mucho más
  simple.
* Si todos los clientes se desconectan, el pump pausa el polling
  (deja de pegar a Spotify) y se rearma cuando vuelve a haber
  alguien escuchando.
* El polling tolera fallos: convierte excepciones a eventos
  ``{"event": "error", "message": "..."}`` para que el cliente sepa
  que algo va mal, y reintenta tras un backoff corto.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator
from typing import Any

from . import spotify_service

log = logging.getLogger(__name__)

POLL_INTERVAL_S = 2.0
ERROR_BACKOFF_S = 10.0
# Tras este tiempo sin clientes conectados, el pump se pone "dormido"
# y deja de pegar a Spotify. Próxima conexión lo despierta.
IDLE_SLEEP_S = 1.0


class _StatePump:
    """Single-instance background task that polls Spotify and fans out."""

    def __init__(self) -> None:
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._task: asyncio.Task[None] | None = None
        self._last_snapshot: dict[str, Any] | None = None
        self._last_emit_ts: float = 0.0
        self._lock = asyncio.Lock()

    async def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        """Return a queue that will receive every event. Caller must
        unsubscribe when done; mejor con ``async with subscriber()``."""
        async with self._lock:
            queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
            self._subscribers.add(queue)
            if self._task is None or self._task.done():
                self._task = asyncio.create_task(self._run(), name="spotify-state-pump")
                log.info("spotify state-pump started")
            # Empuja el último snapshot conocido (si lo hay) para
            # que el cliente arranque con algo en vez de esperar al
            # próximo poll. ``snapshot`` vs ``update`` no importa
            # mucho desde el wire — usamos snapshot para indicar
            # "primera carga".
            if self._last_snapshot is not None:
                await queue.put({"event": "snapshot", "state": self._last_snapshot})
        return queue

    async def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        async with self._lock:
            self._subscribers.discard(queue)
            # Marca la cola con un None para desbloquear al consumer.
            try:
                queue.put_nowait({"event": "_closed"})
            except asyncio.QueueFull:
                pass

    async def _run(self) -> None:
        """Background loop. Polls Spotify and emits to subscribers."""
        try:
            while True:
                if not self._subscribers:
                    await asyncio.sleep(IDLE_SLEEP_S)
                    if not self._subscribers:
                        # Continuamos durmiendo en silencio; si no
                        # vuelve nadie en breve, salimos del loop y
                        # el próximo subscribe arranca otro task.
                        await asyncio.sleep(IDLE_SLEEP_S)
                        if not self._subscribers:
                            log.info("spotify state-pump: idle, stopping")
                            return
                    continue
                try:
                    snapshot = await spotify_service.current_state()
                except Exception as exc:  # noqa: BLE001
                    log.warning("state-pump: current_state raised: %s", exc)
                    await self._emit({"event": "error", "message": str(exc)})
                    await asyncio.sleep(ERROR_BACKOFF_S)
                    continue

                # Solo emitimos updates cuando el snapshot CAMBIA. El
                # progress_ms cambia siempre, así que lo redondeamos
                # a segundos para no inundar el wire.
                if _has_changed(self._last_snapshot, snapshot):
                    self._last_snapshot = snapshot
                    self._last_emit_ts = time.monotonic()
                    await self._emit({"event": "update", "state": snapshot})
                await asyncio.sleep(POLL_INTERVAL_S)
        finally:
            log.info("spotify state-pump exited")

    async def _emit(self, payload: dict[str, Any]) -> None:
        """Broadcast ``payload`` to every subscriber (best-effort)."""
        # Snapshot the set so we can iterate without holding the lock
        # — `put_nowait` cannot block, peor caso droppeamos un evento
        # en un cliente lento (mejor que bloquear a todos).
        subs = list(self._subscribers)
        for queue in subs:
            try:
                queue.put_nowait(payload)
            except asyncio.QueueFull:
                log.info("state-pump: dropping event for slow subscriber")


def _has_changed(prev: dict[str, Any] | None, current: dict[str, Any]) -> bool:
    """True si algún campo "interesante" cambió entre snapshots.

    Compara *todo menos progress_ms*. progress_ms cambia en cada poll
    pero el cliente lo interpola localmente entre eventos; emitirlo
    cada 2s sería ruido.
    """
    if prev is None:
        return True
    keys = (
        "available", "playing", "shuffle", "repeat_state",
        "liked", "duration_ms",
    )
    if any(prev.get(k) != current.get(k) for k in keys):
        return True
    # Comparar track por uri es suficiente.
    prev_uri = ((prev.get("track") or {}).get("uri"))
    curr_uri = ((current.get("track") or {}).get("uri"))
    if prev_uri != curr_uri:
        return True
    # Cambios de device también son updates.
    prev_dev = (prev.get("device") or {}).get("id")
    curr_dev = (current.get("device") or {}).get("id")
    if prev_dev != curr_dev:
        return True
    # Volumen cambia con suficiente "interés" como para emitir.
    prev_vol = (prev.get("device") or {}).get("volume_percent")
    curr_vol = (current.get("device") or {}).get("volume_percent")
    if prev_vol != curr_vol:
        return True
    return False


_PUMP = _StatePump()


async def event_stream() -> AsyncIterator[bytes]:
    """Yields SSE-formatted bytes for an HTTP streaming response."""
    queue = await _PUMP.subscribe()
    try:
        # Always send a comment heartbeat first so the client knows
        # we're alive (some proxies hold the response until the first
        # byte arrives).
        yield b": kiwi-spotify-stream\n\n"
        while True:
            payload = await queue.get()
            if payload.get("event") == "_closed":
                return
            data = json.dumps(payload, ensure_ascii=False)
            event_type = payload.get("event") or "message"
            chunk = f"event: {event_type}\ndata: {data}\n\n".encode()
            yield chunk
    finally:
        await _PUMP.unsubscribe(queue)
