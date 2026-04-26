"""Vertex AI Live API proxy.

Opens a bidirectional Gemini Live session for the duration of one tablet
session and shuttles audio + transcripts in both directions. Authenticates
via Application Default Credentials provided by the Cloud Run runtime
service account, so no API key ever lives in the backend code or env.

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


async def proxy(ws: WebSocket, settings: Settings) -> None:
    """Run the tablet ↔ Gemini Live proxy until either side disconnects."""
    try:
        client = genai.Client(
            vertexai=True,
            project=settings.gcp_project,
            location=settings.gcp_location,
        )

        config = types.LiveConnectConfig(
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
                parts=[types.Part(text=settings.gemini_system_prompt)],
            ),
            input_audio_transcription=types.AudioTranscriptionConfig(),
            output_audio_transcription=types.AudioTranscriptionConfig(),
            # Manual activity detection: the tablet sends explicit
            # activity_start / activity_end markers (driven by the
            # user's tap) so Gemini doesn't have to guess turn
            # boundaries. The automatic VAD has a known bug where the
            # session stops responding after the first turn (see
            # python-genai #1657, cookbook #977); driving the markers
            # ourselves sidesteps it entirely.
            realtime_input_config=types.RealtimeInputConfig(
                automatic_activity_detection=types.AutomaticActivityDetection(
                    disabled=True,
                ),
            ),
            # Keep the session resumable + the context bounded so a
            # long conversation doesn't blow through the token budget.
            session_resumption=types.SessionResumptionConfig(),
            context_window_compression=types.ContextWindowCompressionConfig(
                sliding_window=types.SlidingWindow(),
            ),
        )

        async with client.aio.live.connect(
            model=settings.gemini_model,
            config=config,
        ) as gemini_session:
            await asyncio.gather(
                _tablet_to_gemini(ws, gemini_session),
                _gemini_to_tablet(gemini_session, ws),
            )
    except WebSocketDisconnect:
        log.info("tablet disconnected")
    except Exception as exc:
        # Surface the failure to the tablet so the UI can render a useful
        # error instead of a silent disconnect / EOF.
        log.exception("Gemini Live error")
        await _safe_send(
            ws,
            {
                "type": protocol.TYPE_ERROR,
                "message": f"{type(exc).__name__}: {exc}",
            },
        )


async def _tablet_to_gemini(ws: WebSocket, gemini_session) -> None:
    """Forward audio chunks and turn boundaries from the tablet to Gemini."""
    while True:
        message = await ws.receive_json()
        kind = message.get("type")

        if kind == protocol.TYPE_ACTIVITY_START:
            # User just tapped to start their turn. With manual activity
            # detection this is the only way Gemini learns the user is
            # speaking; without it Gemini sits silent and never responds.
            await gemini_session.send_realtime_input(
                activity_start=types.ActivityStart(),
            )
            continue

        if kind == protocol.TYPE_AUDIO_INPUT:
            data = message.get("data")
            if not data:
                continue
            try:
                pcm = base64.b64decode(data)
            except (ValueError, TypeError):
                continue
            await gemini_session.send_realtime_input(
                media=types.Blob(data=pcm, mime_type="audio/pcm;rate=16000"),
            )
            continue

        if kind == protocol.TYPE_ACTIVITY_END or kind == protocol.TYPE_AUDIO_END:
            # User tapped again to end their turn (activity.end is the
            # canonical signal; audio.end is kept for compatibility with
            # older clients).
            await gemini_session.send_realtime_input(
                activity_end=types.ActivityEnd(),
            )
            continue

        if kind == protocol.TYPE_SESSION_END:
            return


async def _gemini_to_tablet(gemini_session, ws: WebSocket) -> None:
    """Forward audio + transcripts from Gemini to the tablet."""
    async for response in gemini_session.receive():
        # Audio chunks arrive on the convenience `data` attribute when the
        # model's turn includes inline audio.
        audio = getattr(response, "data", None)
        if audio:
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
            await _safe_send(
                ws,
                {"type": protocol.TYPE_TRANSCRIPT_INPUT, "text": input_tx.text},
            )

        output_tx = getattr(sc, "output_transcription", None)
        if output_tx is not None and getattr(output_tx, "text", None):
            await _safe_send(
                ws,
                {"type": protocol.TYPE_TRANSCRIPT_OUTPUT, "text": output_tx.text},
            )

        if getattr(sc, "turn_complete", False):
            await _safe_send(ws, {"type": protocol.TYPE_RESPONSE_END})


async def _safe_send(ws: WebSocket, payload: dict) -> None:
    """Best-effort JSON send that swallows disconnects.

    Either side of the proxy may close while the other is mid-message; we
    don't want a stray `WebSocketDisconnect` from the tablet socket to
    propagate into the Gemini side and abort the whole `asyncio.gather`.
    """
    try:
        await ws.send_json(payload)
    except WebSocketDisconnect:
        pass
    except RuntimeError:
        # Starlette raises RuntimeError if you try to send on a socket
        # that has already been closed by the framework.
        pass
