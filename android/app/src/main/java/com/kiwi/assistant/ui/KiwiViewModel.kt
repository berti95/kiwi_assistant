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
 * Flujo (V1, manual tap-to-talk dentro de la misma sesión Gemini):
 *
 *   Idle ── tap ──▶ Standby           (abre WebSocket, sesión Gemini lista)
 *   Standby ── tap ──▶ Listening      (activity.start, captura on)
 *   Listening ── tap ──▶ Processing   (activity.end, captura off)
 *   Processing ── audio.output ──▶ Responding
 *   Responding ── response.end ──▶ Standby (siguiente turno)
 *   Cualquier estado activo ── long-press ──▶ Idle (sesión cerrada)
 *   Cualquier estado ── error ──▶ Error (con limpieza)
 *
 * Manual activity detection sortea el bug del VAD automático de Gemini Live
 * que deja la sesión muda tras el primer turno (python-genai #1657,
 * cookbook #977). Cada turno tiene bordes explícitos via tap.
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
            KiwiState.Standby -> startUserTurn()
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
        // Show Standby right away; SessionReady is just confirmation that
        // the backend handshake succeeded, no UI change needed.
        _state.value = KiwiState.Standby
        s.connect { event ->
            viewModelScope.launch(Dispatchers.Main) { handleEvent(event) }
        }
    }

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
            KiwiSessionEvent.SessionReady -> android.util.Log.i(TAG, "session.ready")

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
                android.util.Log.i(TAG, "response.end → drain → Standby")
                waitForAudioAndGoStandby()
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
     * Wait for AudioTrack to actually finish playing the response before
     * showing Standby — otherwise the UI says "ready for next turn" while
     * Kiwi is still mid-sentence.
     */
    private fun waitForAudioAndGoStandby() {
        if (_state.value is KiwiState.Idle || _state.value is KiwiState.Error) return
        viewModelScope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)  // AudioTrack internal buffer drain
            if (_state.value is KiwiState.Idle || _state.value is KiwiState.Error) return@launch
            _state.value = KiwiState.Standby
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
