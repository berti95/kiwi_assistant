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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquesta la sesión completa de Kiwi:
 *   tap → conexión WebSocket → captura de micro → respuesta de audio.
 *
 * El flujo (V1, conversación continua):
 *   Idle ── tap ──▶ Listening (captura activa, audio.input streaming)
 *   Listening ── audio.output ──▶ Responding (Kiwi habla; captura sigue
 *                                              activa por si interrumpes)
 *   Responding ── response.end ──▶ Listening (próximo turno del usuario)
 *   Cualquier estado activo ── tap ──▶ Idle (sesión cerrada)
 *   Cualquier estado ── error ──▶ Error (con limpieza)
 *
 * Gemini Live se encarga del end-of-turn vía VAD: tú hablas, paras, él
 * responde, vuelves a hablar, etc., todo dentro de una sola sesión
 * WebSocket. Para cerrar tocas la pantalla.
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
            KiwiState.Listening,
            KiwiState.Processing,
            is KiwiState.Responding,
            -> endSession()
            is KiwiState.Error -> _state.value = KiwiState.Idle
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

            is KiwiSessionEvent.AudioOutput -> {
                playbackQueue.trySend(event.pcm)
                // First chunk of the assistant's reply: visually flip from
                // Listening to Responding. We don't stop capture so the
                // user can interrupt; Gemini Live handles barge-in.
                if (_state.value is KiwiState.Listening) {
                    _state.value = KiwiState.Responding(transcript = "")
                }
            }

            is KiwiSessionEvent.InputTranscript -> Unit
            is KiwiSessionEvent.OutputTranscript -> appendOutputTranscript(event.text)

            KiwiSessionEvent.ResponseEnd -> {
                // Gemini finished THIS turn. The session stays open and
                // capture keeps running so the user can follow up; we
                // just bring the UI back to Listening.
                if (_state.value !is KiwiState.Idle && _state.value !is KiwiState.Error) {
                    _state.value = KiwiState.Listening
                }
            }

            is KiwiSessionEvent.Closed -> {
                val current = _state.value
                when {
                    current is KiwiState.Error -> Unit
                    current is KiwiState.Idle -> Unit
                    else -> {
                        // Server closed mid-session (cold start, timeout,
                        // network blip…). Surface code+reason so we can
                        // tell what happened.
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

    private fun startCapture() {
        if (_state.value != KiwiState.Listening) return
        val ok = capture.start(viewModelScope) { chunk -> session?.sendAudio(chunk) }
        if (!ok) {
            _state.value = KiwiState.Error("No se pudo iniciar la captura de audio.")
            cleanup()
        }
    }

    private fun appendOutputTranscript(chunk: String) {
        val current = _state.value
        val previous = (current as? KiwiState.Responding)?.transcript ?: ""
        _state.value = KiwiState.Responding(transcript = previous + chunk)
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
}
