"""WebSocket session handler.

V1 echo mode: validates the handshake and echoes audio frames back as
``audio.output``, plus placeholder transcripts. This lets the Android
client exercise the full protocol before the Vertex AI Live integration
lands.
"""

import json
import logging

from fastapi import WebSocket, WebSocketDisconnect

from . import protocol
from .auth import is_valid_api_key

log = logging.getLogger(__name__)


async def run_session(ws: WebSocket) -> None:
    await ws.accept()

    if not await _do_handshake(ws):
        return

    await ws.send_json({"type": protocol.TYPE_SESSION_READY})

    try:
        while True:
            raw = await ws.receive_text()
            message = _parse(raw)
            if message is None:
                await ws.send_json(
                    {"type": protocol.TYPE_ERROR, "message": "malformed JSON"}
                )
                continue
            keep_running = await _handle_message(ws, message)
            if not keep_running:
                return
    except WebSocketDisconnect:
        log.info("client disconnected")


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


async def _handle_message(ws: WebSocket, message: dict) -> bool:
    """Process one client message. Returns False when the session ends."""
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
