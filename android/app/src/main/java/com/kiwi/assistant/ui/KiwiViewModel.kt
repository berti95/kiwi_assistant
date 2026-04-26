package com.kiwi.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioPlaybackManager
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
 * Flujo (V2, un tap = un borde de turno; sin paso intermedio de
 * "Toca para hablar"):
 *
 *   Idle ── tap ──▶ Connecting       (abre WebSocket)
 *   Connecting ── session.ready ──▶ Listening  (auto: activity.start + captura)
 *   Listening ── tap ──▶ Processing  (activity.end, captura off)
 *   Processing ── audio.output ──▶ Responding
 *   Responding ── response.end + drain ──▶ Listening  (auto: siguiente turno)
 *   Cualquier estado activo ── long-press ──▶ Idle (sesión cerrada)
 *   Cualquier estado ── error ──▶ Error (con limpieza)
 *
 * El primer tap abre la sesión y abre el micro en cuanto el server
 * confirma; los taps siguientes solo cierran el turno actual. El micro
 * vuelve a abrirse solo después de que Kiwi termine de hablar, así que
 * para una conversación normal solo hace falta 1 tap por turno (al
 * acabar de hablar). Long-press cierra la sesión entera.
 *
 * Manual activity detection (server-side) sortea el bug multi-turn de
 * Vertex Live API y va emparejado con la rotación per-turn de la
 * sesión Gemini que hace el backend.
 */
class KiwiViewModel : ViewModel() {

    private val _state = MutableStateFlow<KiwiState>(KiwiState.Idle)
    val state: StateFlow<KiwiState> = _state.asStateFlow()

    private val capture = AudioCaptureManager()
    private val playback = AudioPlaybackManager()
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

    /** Long press anywhere → close the conversation entirely. */
    fun onLongPress() {
        if (_state.value !is KiwiState.Idle) endSession()
    }

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
        session?.sendActivityStart()
        val ok = capture.start(viewModelScope) { chunk -> session?.sendAudio(chunk) }
        if (!ok) {
            _state.value = KiwiState.Error("No se pudo iniciar la captura de audio.")
            cleanup()
            return
        }
        _state.value = KiwiState.Listening
    }

    private fun endUserTurn() {
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
        super.onCleared()
    }

    private companion object {
        const val TAG = "KiwiViewModel"
    }
}
