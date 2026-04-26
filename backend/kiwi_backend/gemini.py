"""Vertex AI Live API proxy.

Multi-turn voice sessions on Gemini Live get stuck after the first
response in our reproducible test — both with automatic VAD and with
manual activity markers. With manual VAD the tablet sent
``activity_start`` → audio → ``activity_end`` for turn 2 just fine, the
proxy forwarded all three to Gemini, and Gemini simply never produced a
``turn_complete`` (commit 5d1da4d added the diagnostics that made this
concrete: see Cloud Run trace where ``response 2`` never arrives).

Workaround: open a fresh Gemini Live session **per user turn** and
thread the conversation history into the ``system_instruction`` of each
new session, so context survives across turns. The tablet sees one
continuous WebSocket and one continuous conversation; we just rotate
the upstream Live session under the covers. Cost is ~0.5–1 s extra at
the start of every turn while the new Live session handshakes — fine
for a kid's chat, far better than a session that goes mute.

Wire formats:
    Tablet → Gemini :  base64 PCM 16-bit / 16 kHz / mono
    Gemini → Tablet :  base64 PCM 16-bit / 24 kHz / mono
"""

import asyncio
import base64
import logging

from fastapi import WebSocket, WebSocketDisconnect
from google import genai
from google.genai import types

from . import protocol
from .settings import Settings

log = logging.getLogger(__name__)


class _SessionEndError(Exception):
    """Tablet asked us to close the whole session, mid-turn."""


class _TurnCancelledError(Exception):
    """Tablet asked us to abandon the current turn (no speech detected).

    Raised inside ``_run_one_turn`` so the outer ``async with
    client.aio.live.connect`` block exits and the upstream Gemini Live
    session is closed without producing a response, and so the proxy
    loop knows to skip the history append for this turn.
    """


async def proxy(ws: WebSocket, settings: Settings) -> None:
    """Run the tablet ↔ Gemini Live proxy until either side disconnects."""
    history: list[dict[str, str]] = []
    try:
        client = genai.Client(
            vertexai=True,
            project=settings.gcp_project,
            location=settings.gcp_location,
        )
        while True:
            # Wait for the user to tap-to-talk for the next turn.
            message = await ws.receive_json()
            kind = message.get("type")
            if kind == protocol.TYPE_SESSION_END:
                log.info(
                    "session.end received from tablet after %d turns",
                    len(history),
                )
                return
            if kind != protocol.TYPE_ACTIVITY_START:
                # Audio chunks or stray markers that arrive before
                # activity_start belong to nothing — drop them.
                continue

            turn_index = len(history) + 1
            log.info(
                "turn %d: opening fresh Gemini Live session (history=%d turns)",
                turn_index,
                len(history),
            )
            config = _build_config(settings, history)
            try:
                async with client.aio.live.connect(
                    model=settings.gemini_model,
                    config=config,
                ) as gemini_session:
                    await gemini_session.send_realtime_input(
                        activity_start=types.ActivityStart(),
                    )
                    user_text, kiwi_text = await _run_one_turn(
                        ws, gemini_session, turn_index,
                    )
                    history.append(
                        {"user": user_text.strip(), "kiwi": kiwi_text.strip()},
                    )
            except _TurnCancelledError:
                log.info("turn %d: cancelled by tablet (no speech)", turn_index)
                # Drop the upstream session, keep the WebSocket open,
                # and loop back to wait for the next activity_start.
                continue
            except _SessionEndError:
                log.info(
                    "session.end received mid-turn after %d turns",
                    len(history),
                )
                return
    except WebSocketDisconnect:
        log.info("tablet disconnected after %d turns", len(history))
    except Exception as exc:
        log.exception("Gemini Live error")
        await _safe_send(
            ws,
            {
                "type": protocol.TYPE_ERROR,
                "message": f"{type(exc).__name__}: {exc}",
            },
        )


def _build_config(
    settings: Settings,
    history: list[dict[str, str]],
) -> types.LiveConnectConfig:
    """Build a per-turn LiveConnectConfig with the prior turns inlined."""
    base_prompt = settings.gemini_system_prompt
    if history:
        formatted = "\n\n".join(
            f"Usuario: {h['user']}\nKiwi: {h['kiwi']}" for h in history
        )
        system_text = (
            f"{base_prompt}\n\n"
            f"Hasta ahora la conversación con el usuario en esta misma "
            f"sesión ha sido:\n\n{formatted}\n\n"
            f"Continúa la conversación con coherencia, recordando lo "
            f"anterior, sin saludarle de nuevo ni presentarte otra vez."
        )
    else:
        system_text = base_prompt

    return types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                    voice_name=settings.gemini_voice,
                ),
            ),
            language_code="es-ES",
        ),
        system_instruction=types.Content(
            parts=[types.Part(text=system_text)],
        ),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        # Manual activity detection: the tablet drives turn boundaries
        # via tap. Each Gemini Live session here only ever handles ONE
        # turn so there's no multi-turn VAD edge case to trip on.
        realtime_input_config=types.RealtimeInputConfig(
            automatic_activity_detection=types.AutomaticActivityDetection(
                disabled=True,
            ),
        ),
    )


async def _run_one_turn(
    ws: WebSocket,
    gemini_session,
    turn_index: int,
) -> tuple[str, str]:
    """Drive one user turn against the current Gemini session.

    Returns ``(user_transcript, kiwi_transcript)`` so the proxy loop
    can append them to the conversation history that will be threaded
    into the next session's system instruction.
    """
    user_text_parts: list[str] = []
    kiwi_text_parts: list[str] = []
    audio_chunks_in = 0
    audio_bytes_out = 0

    async def tablet_to_gemini() -> None:
        nonlocal audio_chunks_in
        while True:
            message = await ws.receive_json()
            kind = message.get("type")
            if kind == protocol.TYPE_AUDIO_INPUT:
                data = message.get("data")
                if not data:
                    continue
                try:
                    pcm = base64.b64decode(data)
                except (ValueError, TypeError):
                    continue
                audio_chunks_in += 1
                await gemini_session.send_realtime_input(
                    media=types.Blob(data=pcm, mime_type="audio/pcm;rate=16000"),
                )
            elif kind in (protocol.TYPE_ACTIVITY_END, protocol.TYPE_AUDIO_END):
                log.info(
                    "turn %d: activity_end after %d audio chunks (~%d ms)",
                    turn_index,
                    audio_chunks_in,
                    audio_chunks_in * 50,
                )
                await gemini_session.send_realtime_input(
                    activity_end=types.ActivityEnd(),
                )
                return
            elif kind == protocol.TYPE_TURN_CANCEL:
                log.info(
                    "turn %d: cancel requested after %d audio chunks",
                    turn_index,
                    audio_chunks_in,
                )
                raise _TurnCancelledError()
            elif kind == protocol.TYPE_SESSION_END:
                raise _SessionEndError()

    async def gemini_to_tablet() -> None:
        nonlocal audio_bytes_out
        first_chunk_logged = False
        async for response in gemini_session.receive():
            audio = getattr(response, "data", None)
            if audio:
                if not first_chunk_logged:
                    log.info(
                        "turn %d: first audio chunk from Gemini",
                        turn_index,
                    )
                    first_chunk_logged = True
                audio_bytes_out += len(audio)
                await _safe_send(
                    ws,
                    {
                        "type": protocol.TYPE_AUDIO_OUTPUT,
                        "data": base64.b64encode(audio).decode("ascii"),
                    },
                )

            sc = getattr(response, "server_content", None)
            if sc is None:
                continue

            input_tx = getattr(sc, "input_transcription", None)
            if input_tx is not None and getattr(input_tx, "text", None):
                user_text_parts.append(input_tx.text)
                await _safe_send(
                    ws,
                    {
                        "type": protocol.TYPE_TRANSCRIPT_INPUT,
                        "text": input_tx.text,
                    },
                )

            output_tx = getattr(sc, "output_transcription", None)
            if output_tx is not None and getattr(output_tx, "text", None):
                kiwi_text_parts.append(output_tx.text)
                await _safe_send(
                    ws,
                    {
                        "type": protocol.TYPE_TRANSCRIPT_OUTPUT,
                        "text": output_tx.text,
                    },
                )

            if getattr(sc, "turn_complete", False):
                log.info(
                    "turn %d: turn_complete (audio=%d bytes, text=%r)",
                    turn_index,
                    audio_bytes_out,
                    "".join(kiwi_text_parts),
                )
                await _safe_send(ws, {"type": protocol.TYPE_RESPONSE_END})
                return

    await asyncio.gather(tablet_to_gemini(), gemini_to_tablet())
    return "".join(user_text_parts), "".join(kiwi_text_parts)


async def _safe_send(ws: WebSocket, payload: dict) -> None:
    """Best-effort JSON send that swallows disconnects.

    Either side of the proxy may close while the other is mid-message;
    we don't want a stray ``WebSocketDisconnect`` from the tablet
    socket to propagate into the Gemini side and abort the whole
    ``asyncio.gather``.
    """
    try:
        await ws.send_json(payload)
    except WebSocketDisconnect:
        pass
    except RuntimeError:
        # Starlette raises RuntimeError if you try to send on a socket
        # that has already been closed by the framework.
        pass
