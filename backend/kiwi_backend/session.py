"""WebSocket session handler.

Two operating modes, picked at handshake time based on `settings.gcp_project`:

- Vertex AI Live (production): the handshake completes, and the rest of the
  socket is handed off to `gemini.proxy()` which streams audio + transcripts
  through the Gemini Live API.
- Echo (local dev / tests / pre-Vertex sanity checks): the existing in-process
  loop echoes audio.input back as audio.output and answers audio.end with
  placeholder transcripts. No external service is hit.
"""

import json
import logging

from fastapi import WebSocket, WebSocketDisconnect

from . import protocol
from .auth import is_valid_api_key
from .settings import settings

log = logging.getLogger(__name__)


async def run_session(ws: WebSocket) -> None:
    await ws.accept()

    if not await _do_handshake(ws):
        return

    await ws.send_json({"type": protocol.TYPE_SESSION_READY})

    if settings.gcp_project:
        # Anything that escapes the lazy import or the proxy itself would
        # otherwise propagate up to Starlette and tear the socket down with
        # no clean close frame, which the OkHttp client surfaces as a bare
        # EOFException with no diagnostic value. Wrap both so the tablet
        # always gets a typed error before disconnect.
        try:
            from . import gemini
        except Exception as exc:
            log.exception("failed to import gemini module")
            await _safe_send(
                ws,
                {
                    "type": protocol.TYPE_ERROR,
                    "message": f"import gemini: {type(exc).__name__}: {exc}",
                },
            )
            return

        try:
            await gemini.proxy(ws, settings)
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            log.exception("uncaught proxy error")
            await _safe_send(
                ws,
                {
                    "type": protocol.TYPE_ERROR,
                    "message": f"proxy crashed: {type(exc).__name__}: {exc}",
                },
            )
        return

    await _run_echo_loop(ws)


async def _do_handshake(ws: WebSocket) -> bool:
    try:
        raw = await ws.receive_text()
    except WebSocketDisconnect:
        return False

    message = _parse(raw)
    if message is None:
        await ws.close(code=protocol.CLOSE_BAD_REQUEST, reason="malformed JSON")
        return False

    if message.get("type") != protocol.TYPE_SESSION_START:
        await ws.close(
            code=protocol.CLOSE_EXPECTED_SESSION_START,
            reason="expected session.start",
        )
        return False

    if not is_valid_api_key(message.get("api_key")):
        await ws.close(code=protocol.CLOSE_INVALID_API_KEY, reason="invalid api_key")
        return False

    return True


async def _run_echo_loop(ws: WebSocket) -> None:
    try:
        while True:
            raw = await ws.receive_text()
            message = _parse(raw)
            if message is None:
                await ws.send_json(
                    {"type": protocol.TYPE_ERROR, "message": "malformed JSON"}
                )
                continue
            keep_running = await _handle_echo(ws, message)
            if not keep_running:
                return
    except WebSocketDisconnect:
        log.info("client disconnected")


async def _handle_echo(ws: WebSocket, message: dict) -> bool:
    """Echo branch of the session: returns False when the session ends."""
    message_type = message.get("type")

    if message_type == protocol.TYPE_AUDIO_INPUT:
        # Echo: ship the same bytes back so the tablet can validate its
        # capture+playback path before Gemini is wired in.
        data = message.get("data", "")
        await ws.send_json({"type": protocol.TYPE_AUDIO_OUTPUT, "data": data})
        return True

    if message_type == protocol.TYPE_AUDIO_END:
        await ws.send_json(
            {"type": protocol.TYPE_TRANSCRIPT_INPUT, "text": "[echo placeholder]"}
        )
        await ws.send_json(
            {"type": protocol.TYPE_TRANSCRIPT_OUTPUT, "text": "[echo placeholder]"}
        )
        await ws.send_json({"type": protocol.TYPE_RESPONSE_END})
        return True

    if message_type == protocol.TYPE_SESSION_END:
        await ws.close()
        return False

    await ws.send_json(
        {
            "type": protocol.TYPE_ERROR,
            "message": f"unexpected message type: {message_type!r}",
        }
    )
    return True


def _parse(raw: str) -> dict | None:
    try:
        message = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return message if isinstance(message, dict) else None


async def _safe_send(ws: WebSocket, payload: dict) -> None:
    """Best-effort send that swallows disconnects.

    Used at the failure path where the socket may already be closing.
    """
    try:
        await ws.send_json(payload)
    except WebSocketDisconnect:
        pass
    except RuntimeError:
        # Starlette raises RuntimeError if you try to send on a socket
        # that has already been closed by the framework.
        pass
