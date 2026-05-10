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
import time

from fastapi import WebSocket, WebSocketDisconnect
from google import genai
from google.genai import types

from . import protocol, tools, usage
from .settings import Settings

log = logging.getLogger(__name__)

# Hard upper bound on a single user→Kiwi exchange. The tablet's VAD
# already closes turns at ~1.2 s of silence and Gemini Live wraps up
# the response in seconds; anything past 3 minutes is a stuck Gemini
# session or a tablet that's frozen mid-stream. We bail rather than
# leave the upstream Live session billing in the background.
_PER_TURN_HARD_TIMEOUT_S = 180

# How long we wait between turns for the next ``activity_start`` from
# the tablet. The tablet's own no-speech timeout is 15 s and a healthy
# inter-turn drain is <5 s, so 120 s is a generous defence against a
# zombie WebSocket (network drop without TCP-RST, app force-stopped
# but socket lingering) keeping a Cloud Run instance + Gemini quota
# alive for nothing.
_BETWEEN_TURNS_IDLE_TIMEOUT_S = 120


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
    started_at_ms = int(time.time() * 1000)
    stats = usage.ProxyStats()
    try:
        # Google AI Studio (not Vertex Live) — see settings.gemini_api_key
        # for the rationale. The same google-genai SDK serves both; only
        # the Client constructor differs.
        client = genai.Client(api_key=settings.gemini_api_key)
        while True:
            # Wait for the user to tap-to-talk for the next turn.
            try:
                message = await asyncio.wait_for(
                    ws.receive_json(),
                    timeout=_BETWEEN_TURNS_IDLE_TIMEOUT_S,
                )
            except TimeoutError:
                log.warning(
                    "WS idle for %ds between turns after %d turns — closing "
                    "to stop Gemini billing on a zombie session",
                    _BETWEEN_TURNS_IDLE_TIMEOUT_S,
                    len(history),
                )
                await _safe_send(
                    ws,
                    {
                        "type": protocol.TYPE_ERROR,
                        "message": "idle timeout",
                    },
                )
                return
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
                    user_text, kiwi_text = await asyncio.wait_for(
                        _run_one_turn(ws, gemini_session, turn_index, stats),
                        timeout=_PER_TURN_HARD_TIMEOUT_S,
                    )
                    history.append(
                        {"user": user_text.strip(), "kiwi": kiwi_text.strip()},
                    )
                    stats.turn_count += 1
            except _TurnCancelledError:
                log.info("turn %d: cancelled by tablet (no speech)", turn_index)
                # Drop the upstream session, keep the WebSocket open,
                # and loop back to wait for the next activity_start.
                continue
            except TimeoutError:
                log.warning(
                    "turn %d: hit hard timeout (%ds) — closing upstream "
                    "Gemini session and waiting for next turn",
                    turn_index,
                    _PER_TURN_HARD_TIMEOUT_S,
                )
                await _safe_send(
                    ws,
                    {
                        "type": protocol.TYPE_ERROR,
                        "message": "turn timeout",
                    },
                )
                # The async-with around live.connect already closed
                # the upstream session via the cancellation propagation.
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
    finally:
        # Persiste estadísticas de la conversación (incluso si terminó
        # por excepción / disconnect / timeout). Si no hubo turnos
        # completos no merece la pena guardar la entrada.
        if stats.turn_count > 0:
            try:
                await asyncio.to_thread(
                    usage.log_conversation,
                    started_at_ms=started_at_ms,
                    ended_at_ms=int(time.time() * 1000),
                    turn_count=stats.turn_count,
                    audio_in_seconds=stats.audio_in_seconds,
                    audio_out_seconds=stats.audio_out_seconds,
                    tool_call_names=stats.tool_call_names,
                )
            except Exception:  # noqa: BLE001
                log.exception("usage.log_conversation failed")


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
        # Function-calling: Gemini composes the registered tools itself
        # whenever the user asks for something that needs one (the time,
        # the weather one day, calendar events, …). Empty list = audio-only.
        tools=tools.gemini_tools(),
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
    stats: usage.ProxyStats,
) -> tuple[str, str]:
    """Drive one user turn against the current Gemini session.

    Returns ``(user_transcript, kiwi_transcript)`` so the proxy loop
    can append them to the conversation history that will be threaded
    into the next session's system instruction. Acumula contadores en
    ``stats`` para el log de uso al cerrar la conversación.
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
                    audio=types.Blob(data=pcm, mime_type="audio/pcm;rate=16000"),
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

            tool_call = getattr(response, "tool_call", None)
            if tool_call is not None:
                await _handle_tool_call(
                    ws, gemini_session, tool_call, turn_index, stats,
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
                user_text = "".join(user_text_parts).strip()
                log.info(
                    "turn %d: turn_complete user=%r kiwi=%r (audio=%d bytes)",
                    turn_index,
                    user_text or "(no transcript)",
                    "".join(kiwi_text_parts),
                    audio_bytes_out,
                )
                await _safe_send(ws, {"type": protocol.TYPE_RESPONSE_END})
                return

    await asyncio.gather(tablet_to_gemini(), gemini_to_tablet())
    # Cada chunk de input son 50 ms (16 kHz frame de 800 muestras × 16
    # bit). Output: 24 kHz mono 16-bit = 48 000 bytes por segundo.
    stats.audio_in_seconds += audio_chunks_in * 0.05
    stats.audio_out_seconds += audio_bytes_out / 48_000.0
    return "".join(user_text_parts), "".join(kiwi_text_parts)


async def _handle_tool_call(
    ws: WebSocket,
    gemini_session,
    tool_call,
    turn_index: int,
    stats: usage.ProxyStats,
) -> None:
    """Run each requested tool and ship the responses back to Gemini.

    Gemini batches function calls into a single ``tool_call`` event
    even when only one is requested, so we always iterate. The dispatch
    is sequential — the built-ins are fast and there's almost never
    more than one in flight, so parallelising would be premature.

    Tools that produce a UI scene (calendar listing, now-playing card,
    …) push it to the tablet via ``scene_sink`` BEFORE the tool's
    response is returned to Gemini, so the screen update lands at the
    same time Kiwi starts speaking.
    """
    function_calls = list(getattr(tool_call, "function_calls", None) or [])
    if not function_calls:
        return

    async def scene_sink(scene: dict) -> None:
        log.info("turn %d: scene push type=%r", turn_index, scene.get("type"))
        await _safe_send(
            ws,
            {"type": protocol.TYPE_SCENE_SET, "scene": scene},
        )

    responses: list[types.FunctionResponse] = []
    for fc in function_calls:
        name = fc.name or ""
        args = dict(fc.args or {})
        log.info("turn %d: tool call %r args=%r", turn_index, name, args)
        if name:
            stats.tool_call_names.append(name)
        result = await tools.dispatch(name, args, on_scene=scene_sink)
        log.info("turn %d: tool %r result=%r", turn_index, name, result)
        responses.append(
            types.FunctionResponse(
                id=fc.id,
                name=name,
                response=result,
                # Force the model to wake up and generate spoken output
                # immediately after our function response. Without this,
                # the native-audio Live model treats the tool return as
                # SCHEDULING_UNSPECIFIED and ends the turn with zero
                # audio bytes — i.e. the user hears nothing after the
                # screen update. INTERRUPT is the closest "speak now"
                # signal the SDK exposes.
                scheduling=types.FunctionResponseScheduling.INTERRUPT,
            ),
        )
    await gemini_session.send_tool_response(function_responses=responses)


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
