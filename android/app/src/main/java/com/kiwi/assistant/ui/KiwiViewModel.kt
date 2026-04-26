package com.kiwi.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioPlaybackManager
import com.kiwi.assistant.audio.SpeechActivityDetector
import com.kiwi.assistant.network.KiwiSession
import com.kiwi.assistant.network.KiwiSessionEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquesta la sesión completa de Kiwi.
 *
 * Flujo (V3, sin botones para hablar — VAD cierra los turnos solo,
 * como hacen LiveKit / Pipecat / OpenAI Realtime):
 *
 *   Idle ── tap ──▶ Connecting        (abre WebSocket)
 *   Connecting ── session.ready ──▶ Listening   (auto: activity.start + captura)
 *   Listening ── silencio post-habla ──▶ Processing  (auto: activity.end)
 *   Processing ── audio.output ──▶ Responding
 *   Responding ── response.end + drain ──▶ Listening  (auto: siguiente turno)
 *   Listening ── tap sin haber hablado ──▶ Listening  (turn.cancel + reset)
 *   Cualquier estado activo ── long-press ──▶ Idle (sesión cerrada)
 *   Cualquier estado ── error ──▶ Error (con limpieza)
 *
 * El usuario solo toca dos veces durante una conversación normal: una
 * para abrir y una larga para cerrar. Mientras tanto Silero VAD
 * decide cuándo el usuario terminó de hablar (silencio de
 * SILENCE_END_OF_TURN_MS tras voz detectada). Si toca sin haber
 * hablado, mandamos turn.cancel y rearmamos el micro silenciosamente.
 *
 * Manual activity detection (server-side) sortea el bug multi-turn de
 * Vertex Live API y va emparejado con la rotación per-turn de la
 * sesión Gemini que hace el backend.
 */
class KiwiViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<KiwiState>(KiwiState.Idle)
    val state: StateFlow<KiwiState> = _state.asStateFlow()

    private val capture = AudioCaptureManager()
    private val playback = AudioPlaybackManager()
    // Silero VAD wrapper. The constructor loads an ONNX model from the
    // APK so we defer it until the user actually starts a session;
    // most of the time the app is just showing the clock and doesn't
    // need it. Holding the Lazy directly (rather than `by lazy`) lets
    // onCleared check isInitialized() before forcing it open just to
    // close it.
    private val detectorLazy: Lazy<SpeechActivityDetector> = lazy {
        SpeechActivityDetector(application)
    }
    private val detector: SpeechActivityDetector get() = detectorLazy.value
    private var session: KiwiSession? = null

    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingPlaybackChunks = AtomicInteger(0)

    private val playbackWorker: Job = viewModelScope.launch(Dispatchers.IO) {
        for (chunk in playbackQueue) {
            try {
                playback.play(chunk)
            } catch (e: Exception) {
                android.util.Log.w("KiwiViewModel", "playback chunk failed", e)
            } finally {
                pendingPlaybackChunks.decrementAndGet()
            }
        }
    }

    private var permissionGranted = false

    fun setMicrophonePermission(granted: Boolean) {
        permissionGranted = granted
    }

    fun onTap() {
        when (_state.value) {
            KiwiState.Idle -> openSession()
            // While the WS handshake is in flight a tap would race with
            // the auto-start that fires on session.ready, so swallow it.
            KiwiState.Connecting -> Unit
            KiwiState.Listening -> endUserTurn()
            is KiwiState.Processing,
            is KiwiState.Responding,
            -> Unit
            is KiwiState.Error -> _state.value = KiwiState.Idle
        }
    }

    /**
     * Public end-conversation entry point. Used by both the close
     * button and the long-press gesture; safe to call in any state
     * (no-op when already Idle).
     */
    fun onEndSession() {
        if (_state.value !is KiwiState.Idle) endSession()
    }

    /** Long press anywhere → close the conversation entirely. */
    fun onLongPress() = onEndSession()

    private fun openSession() {
        if (!permissionGranted) {
            _state.value = KiwiState.Error("Concede permiso de micrófono para usar Kiwi.")
            return
        }
        if (BuildConfig.CLOUD_RUN_URL.isEmpty() || BuildConfig.KIWI_API_KEY.isEmpty()) {
            _state.value = KiwiState.Error(
                "Configura CLOUD_RUN_URL y KIWI_API_KEY en local.properties.",
            )
            return
        }

        android.util.Log.i(TAG, "openSession: connecting…")
        playback.start()
        val s = KiwiSession(BuildConfig.CLOUD_RUN_URL, BuildConfig.KIWI_API_KEY)
        session = s
        // Wait for session.ready before opening the mic — sending
        // activity_start while the WS is still mid-handshake would
        // queue it before session.start in OkHttp's outbound buffer
        // and the server would close the socket on us.
        _state.value = KiwiState.Connecting
        s.connect { event ->
            viewModelScope.launch(Dispatchers.Main) { handleEvent(event) }
        }
    }

    /**
     * Send activity_start and start capturing. Used both right after
     * session.ready (first turn) and after response.end + audio drain
     * (subsequent turns). Caller is responsible for ensuring the
     * WebSocket is open and the previous capture (if any) has been
     * stopped already.
     */
    private fun startUserTurn() {
        android.util.Log.i(TAG, "startUserTurn: sending activity_start")
        detector.reset()
        session?.sendActivityStart()
        val ok = capture.start(viewModelScope) { chunk ->
            session?.sendAudio(chunk)
            // Run VAD in parallel with sending so we can both decide
            // (when the user stops talking) that the turn is over and
            // (when the user taps without speaking) that there's
            // nothing to send to Gemini.
            detector.feed(chunk)
            if (detector.isEndOfTurn(SILENCE_END_OF_TURN_MS)) {
                viewModelScope.launch(Dispatchers.Main) {
                    // Re-check on the main thread to avoid double-firing
                    // if several chunks land before endUserTurn() actually
                    // stops the capture coroutine.
                    if (_state.value is KiwiState.Listening) {
                        android.util.Log.i(TAG, "auto end-of-turn (silence detected)")
                        endUserTurn()
                    }
                }
            }
        }
        if (!ok) {
            _state.value = KiwiState.Error("No se pudo iniciar la captura de audio.")
            cleanup()
            return
        }
        _state.value = KiwiState.Listening
    }

    private fun endUserTurn() {
        if (!detector.userSpoke) {
            // The user tapped to end without ever crossing the speech
            // threshold (mainstream voice agents discard these turns
            // rather than feeding silence to the model). Tell the
            // server to drop the upstream Gemini session for this
            // turn, then silently re-arm the mic.
            android.util.Log.i(TAG, "endUserTurn: no speech, cancelling turn")
            capture.stop()
            session?.sendTurnCancel()
            startUserTurn()
            return
        }
        android.util.Log.i(TAG, "endUserTurn: stopping capture + sending activity_end")
        capture.stop()
        session?.sendActivityEnd()
        _state.value = KiwiState.Processing()
    }

    private fun handleEvent(event: KiwiSessionEvent) {
        when (event) {
            KiwiSessionEvent.SessionReady -> {
                android.util.Log.i(TAG, "session.ready → auto-starting first turn")
                // Long-press during the handshake, or an error, may have
                // already closed the session — only auto-start if we're
                // still in the Connecting state we set in openSession.
                if (_state.value is KiwiState.Connecting) {
                    startUserTurn()
                }
            }

            is KiwiSessionEvent.AudioOutput -> {
                pendingPlaybackChunks.incrementAndGet()
                playbackQueue.trySend(event.pcm)
                val current = _state.value
                if (current is KiwiState.Processing) {
                    android.util.Log.i(TAG, "first audio chunk → Responding")
                    // Carry the user transcript over so the UI keeps
                    // showing what Kiwi heard while it answers.
                    _state.value = KiwiState.Responding(
                        userTranscript = current.userTranscript,
                        kiwiTranscript = "",
                    )
                }
            }

            is KiwiSessionEvent.InputTranscript -> appendInputTranscript(event.text)
            is KiwiSessionEvent.OutputTranscript -> appendOutputTranscript(event.text)

            KiwiSessionEvent.ResponseEnd -> {
                android.util.Log.i(TAG, "response.end → drain → next turn")
                waitForAudioAndStartNextTurn()
            }

            is KiwiSessionEvent.Closed -> {
                val current = _state.value
                when {
                    current is KiwiState.Error -> Unit
                    current is KiwiState.Idle -> Unit
                    else -> {
                        val reason = event.reason.takeIf { it.isNotBlank() }
                        val msg = if (reason != null) {
                            "Sesión cerrada (code=${event.code}, ${reason})"
                        } else {
                            "Sesión cerrada (code=${event.code})"
                        }
                        _state.value = KiwiState.Error(msg)
                        cleanup()
                    }
                }
            }

            is KiwiSessionEvent.Error -> {
                _state.value = KiwiState.Error(event.message)
                cleanup()
            }
        }
    }

    /**
     * Append a fragment of the **user's** transcript (what Gemini heard).
     * Updates whichever state currently holds it (Processing or
     * Responding, in case Gemini's input transcription arrives after
     * the first audio chunk).
     */
    private fun appendInputTranscript(chunk: String) {
        val updated = when (val current = _state.value) {
            is KiwiState.Processing ->
                current.copy(userTranscript = current.userTranscript + chunk)
            is KiwiState.Responding ->
                current.copy(userTranscript = current.userTranscript + chunk)
            else -> return
        }
        _state.value = updated
    }

    private fun appendOutputTranscript(chunk: String) {
        val updated = when (val current = _state.value) {
            is KiwiState.Responding ->
                current.copy(kiwiTranscript = current.kiwiTranscript + chunk)
            // Defensive: in theory we always see the first audio chunk
            // (which moves us to Responding) before any output transcript,
            // but if Gemini ever ships text first we still want to show it.
            is KiwiState.Processing ->
                KiwiState.Responding(
                    userTranscript = current.userTranscript,
                    kiwiTranscript = chunk,
                )
            else -> return
        }
        _state.value = updated
    }

    /**
     * Wait for AudioTrack to actually finish playing the response, then
     * auto-start the next turn — otherwise we'd reopen the mic while
     * Kiwi is still mid-sentence and capture our own playback as input.
     * The 800 ms padding accounts for AudioTrack's internal buffer.
     */
    private fun waitForAudioAndStartNextTurn() {
        if (_state.value is KiwiState.Idle || _state.value is KiwiState.Error) return
        viewModelScope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)
            // The user may have long-pressed during the drain; bail if
            // the session is already gone.
            if (_state.value is KiwiState.Idle || _state.value is KiwiState.Error) return@launch
            startUserTurn()
        }
    }

    private fun endSession() {
        cleanup()
        _state.value = KiwiState.Idle
    }

    private fun cleanup() {
        capture.stop()
        playback.stop()
        session?.close()
        session = null
    }

    override fun onCleared() {
        cleanup()
        playbackQueue.close()
        playbackWorker.cancel()
        if (detectorLazy.isInitialized()) {
            runCatching { detectorLazy.value.close() }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "KiwiViewModel"

        // How long the user has to be silent (after Silero stopped
        // returning isSpeech=true) before we auto-close the turn.
        // Silero already smooths over ~300 ms internally, so the
        // perceived silence-to-Kiwi-replies delay is ~300 ms longer
        // than this. 800 ms here ⇒ feels like ~1.1 s of pause, in line
        // with what conversational voice agents (LiveKit, Pipecat)
        // ship by default.
        const val SILENCE_END_OF_TURN_MS = 800L
    }
}
