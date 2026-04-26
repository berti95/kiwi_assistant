package com.kiwi.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioPlaybackManager
import com.kiwi.assistant.network.KiwiSession
import com.kiwi.assistant.network.KiwiSessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquesta la sesión completa de Kiwi:
 *   tap → conexión WebSocket → captura de micro → respuesta de audio.
 *
 * El flujo (V1, tap-to-activate):
 *   Idle  ── tap ──▶ Listening (captura activa, audio.input streaming)
 *   Listening ── tap ──▶ Processing (audio.end enviado, esperando respuesta)
 *   Processing ── audio.output / transcript ──▶ Responding
 *   Responding ── response.end ──▶ Idle
 *   Cualquier estado ── error / lifecycle stop ──▶ Idle (con limpieza)
 */
class KiwiViewModel : ViewModel() {

    private val _state = MutableStateFlow<KiwiState>(KiwiState.Idle)
    val state: StateFlow<KiwiState> = _state.asStateFlow()

    private val capture = AudioCaptureManager()
    private val playback = AudioPlaybackManager()
    private var session: KiwiSession? = null

    // Audio chunks llegan en el thread de OkHttp. Los empujamos a un canal
    // FIFO con un único consumidor para garantizar orden de reproducción.
    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val playbackWorker: Job = viewModelScope.launch(Dispatchers.IO) {
        // A throw here would otherwise complete the launch with a crash
        // and the whole channel pipeline would silently die. Wrap each
        // chunk independently and keep the worker alive across blips.
        for (chunk in playbackQueue) {
            try {
                playback.play(chunk)
            } catch (e: Exception) {
                android.util.Log.w("KiwiViewModel", "playback chunk failed", e)
            }
        }
    }

    private var permissionGranted = false

    fun setMicrophonePermission(granted: Boolean) {
        permissionGranted = granted
    }

    fun onTap() {
        when (_state.value) {
            KiwiState.Idle -> startSession()
            KiwiState.Listening -> finishUserTurn()
            is KiwiState.Error -> _state.value = KiwiState.Idle
            KiwiState.Processing,
            is KiwiState.Responding,
            -> Unit
        }
    }

    private fun startSession() {
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

        _state.value = KiwiState.Listening
        playback.start()
        val s = KiwiSession(BuildConfig.CLOUD_RUN_URL, BuildConfig.KIWI_API_KEY)
        session = s
        s.connect { event ->
            viewModelScope.launch(Dispatchers.Main) { handleEvent(event) }
        }
    }

    private fun handleEvent(event: KiwiSessionEvent) {
        when (event) {
            KiwiSessionEvent.SessionReady -> startCapture()
            is KiwiSessionEvent.AudioOutput -> playbackQueue.trySend(event.pcm)
            is KiwiSessionEvent.InputTranscript -> Unit
            is KiwiSessionEvent.OutputTranscript -> appendOutputTranscript(event.text)
            KiwiSessionEvent.ResponseEnd -> finishSession()
            is KiwiSessionEvent.Closed -> {
                val current = _state.value
                when {
                    current is KiwiState.Error -> Unit
                    current is KiwiState.Idle -> Unit
                    current is KiwiState.Listening || current is KiwiState.Processing -> {
                        val reason = event.reason.takeIf { it.isNotBlank() }
                        val msg = if (reason != null) {
                            "Sesión cerrada antes de tiempo (code=${event.code}, ${reason})"
                        } else {
                            "Sesión cerrada antes de tiempo (code=${event.code})"
                        }
                        _state.value = KiwiState.Error(msg)
                        cleanup()
                    }
                    else -> finishSession()
                }
            }
            is KiwiSessionEvent.Error -> {
                _state.value = KiwiState.Error(event.message)
                cleanup()
            }
        }
    }

    private fun startCapture() {
        if (_state.value != KiwiState.Listening) return
        val ok = capture.start(viewModelScope) { chunk -> session?.sendAudio(chunk) }
        if (!ok) {
            _state.value = KiwiState.Error("No se pudo iniciar la captura de audio.")
            cleanup()
        }
    }

    private fun finishUserTurn() {
        capture.stop()
        session?.sendAudioEnd()
        _state.value = KiwiState.Processing
    }

    private fun appendOutputTranscript(chunk: String) {
        val current = _state.value
        val previous = (current as? KiwiState.Responding)?.transcript ?: ""
        _state.value = KiwiState.Responding(transcript = previous + chunk)
    }

    private fun finishSession() {
        capture.stop()
        viewModelScope.launch(Dispatchers.IO) {
            // Drain the AudioTrack buffer before tearing it down.
            delay(500)
            playback.stop()
            session?.close()
            session = null
            _state.value = KiwiState.Idle
        }
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
}
