package com.kiwi.assistant.voice

import android.content.Context
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioFocusController
import com.kiwi.assistant.audio.AudioPlaybackManager
import com.kiwi.assistant.audio.SpeechActivityDetector
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.network.KiwiSession
import com.kiwi.assistant.network.KiwiSessionEvent
import com.kiwi.assistant.ui.PipelineState
import com.kiwi.assistant.ui.Scene
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Motor de conversación de Kiwi, extraído del ViewModel para poder
 * reutilizarlo tanto en primer plano (Activity) como en un overlay
 * desde el foreground service (Fase 2b).
 *
 * Posee TODO el pipeline de audio/sesión: WebSocket (KiwiSession),
 * captura + VAD (Silero), reproducción, gestión de turnos, foco de
 * audio y reintentos de conexión. Expone [pipeline] como estado y
 * delega en [Host] lo que NO es pipeline (escenas, device commands,
 * decisión de cerrar tras una respuesta, re-armar wake word).
 *
 * Disciplina de micro: el motor abre su propia captura en cada turno;
 * el caller es responsable de liberar el micro del wake word ANTES de
 * llamar a [open] (igual que hacía el ViewModel).
 *
 * El flujo de estados es idéntico al que tenía el ViewModel:
 *   Idle → Connecting → Listening → Processing → Responding → …
 */
class ConversationEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudRunUrl: String,
    private val apiKey: String,
    private val host: Host,
) {

    /** Acoplamiento con el caller para lo que no es pipeline. */
    interface Host {
        /** Una tool empujó una escena (calendar, now-playing, todo…). */
        fun onSceneSet(scene: Scene)
        /** Una tool pidió una acción local (abrir app, volumen…). */
        fun onDeviceCommand(event: KiwiSessionEvent.DeviceCommand)
        /**
         * Tras drenar una respuesta: true → cerrar la conversación
         * (p.ej. el usuario mandó reproducir algo y se va a consumir),
         * false → abrir el siguiente turno (conversación multi-turno).
         */
        fun shouldCloseAfterResponse(): Boolean
        /**
         * El motor cerró por su cuenta (no-speech, cierre limpio del
         * server, o cierre tras respuesta pasiva). El host re-arma el
         * wake word, refresca home, etc. NO toca el pipeline (ya está
         * en Idle).
         */
        fun onClosed()
    }

    private val _pipeline = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    private val capture = AudioCaptureManager()
    private val playback = AudioPlaybackManager()
    private val audioFocus = AudioFocusController(context)
    private val detectorLazy: Lazy<SpeechActivityDetector> = lazy {
        SpeechActivityDetector(context)
    }
    private val detector: SpeechActivityDetector get() = detectorLazy.value

    private var session: KiwiSession? = null
    private var noSpeechTimeoutJob: Job? = null

    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingPlaybackChunks = AtomicInteger(0)
    private val playbackWorker: Job = scope.launch(Dispatchers.IO) {
        for (chunk in playbackQueue) {
            try {
                playback.play(chunk)
            } catch (e: Exception) {
                KLog.w(TAG, "playback chunk failed", e)
            } finally {
                pendingPlaybackChunks.decrementAndGet()
            }
        }
    }

    private var connectRetryAttempt = 0
    private var sessionReady = false

    // ---- public API -------------------------------------------------

    /**
     * Abre una conversación. El caller debe haber liberado el micro del
     * wake word antes. Valida permiso + config y arranca el WebSocket.
     */
    fun open(micPermissionGranted: Boolean) {
        if (!micPermissionGranted) {
            _pipeline.value = PipelineState.Error(
                "Concede permiso de micrófono para usar Kiwi.",
            )
            return
        }
        if (cloudRunUrl.isEmpty() || apiKey.isEmpty()) {
            _pipeline.value = PipelineState.Error(
                "Configura CLOUD_RUN_URL y KIWI_API_KEY en local.properties.",
            )
            return
        }
        connect()
    }

    private fun connect() {
        sessionReady = false
        KLog.i(TAG, "openSession: connecting…")
        audioFocus.acquire()
        playback.start()
        val s = KiwiSession(cloudRunUrl, apiKey)
        session = s
        _pipeline.value = PipelineState.Connecting
        s.connect { event ->
            scope.launch(Dispatchers.Main) { handleEvent(event) }
        }
    }

    /**
     * Tap del usuario en un estado ACTIVO (no Idle/Error — esos los
     * maneja el host). En Listening termina el turno; en el resto se
     * ignora (handshake en vuelo, etc.).
     */
    fun onActiveTap() {
        when (_pipeline.value) {
            PipelineState.Listening -> endUserTurn()
            else -> Unit
        }
    }

    /** Teardown del pipeline → Idle. No toca wake word / escenas. */
    fun close() {
        noSpeechTimeoutJob?.cancel()
        cleanup()
        connectRetryAttempt = 0
        sessionReady = false
        _pipeline.value = PipelineState.Idle
    }

    /** Liberación definitiva (onCleared / onDestroy del caller). */
    fun shutdown() {
        cleanup()
        playbackQueue.close()
        playbackWorker.cancel()
        if (detectorLazy.isInitialized()) {
            runCatching { detectorLazy.value.close() }
        }
    }

    // ---- turn loop --------------------------------------------------

    private fun startUserTurn() {
        KLog.i(TAG, "startUserTurn: sending activity_start")
        detector.reset()
        session?.sendActivityStart()
        val ok = capture.start(scope) { chunk ->
            session?.sendAudio(chunk)
            detector.feed(chunk)
            if (detector.isEndOfTurn(SILENCE_END_OF_TURN_MS)) {
                scope.launch(Dispatchers.Main) {
                    if (_pipeline.value is PipelineState.Listening) {
                        KLog.i(TAG, "auto end-of-turn (silence detected)")
                        endUserTurn()
                    }
                }
            }
        }
        if (!ok) {
            _pipeline.value = PipelineState.Error("No se pudo iniciar la captura de audio.")
            cleanup()
            return
        }
        _pipeline.value = PipelineState.Listening
        scheduleNoSpeechTimeout()
    }

    private fun scheduleNoSpeechTimeout() {
        noSpeechTimeoutJob?.cancel()
        noSpeechTimeoutJob = scope.launch(Dispatchers.Main) {
            delay(NO_SPEECH_TIMEOUT_MS)
            if (_pipeline.value is PipelineState.Listening && !detector.userSpoke) {
                KLog.i(
                    TAG,
                    "no-speech timeout (${NO_SPEECH_TIMEOUT_MS}ms) — closing to stop billing",
                )
                session?.sendTurnCancel()
                internalClose()
            }
        }
    }

    private fun endUserTurn() {
        noSpeechTimeoutJob?.cancel()
        if (!detector.userSpoke) {
            KLog.i(TAG, "endUserTurn: no speech, cancelling turn")
            capture.stop()
            session?.sendTurnCancel()
            startUserTurn()
            return
        }
        KLog.i(TAG, "endUserTurn: stopping capture + sending activity_end")
        capture.stop()
        session?.sendActivityEnd()
        _pipeline.value = PipelineState.Processing()
    }

    // ---- event handling ---------------------------------------------

    private fun handleEvent(event: KiwiSessionEvent) {
        when (event) {
            KiwiSessionEvent.SessionReady -> {
                KLog.i(TAG, "session.ready → auto-starting first turn")
                sessionReady = true
                connectRetryAttempt = 0
                if (_pipeline.value is PipelineState.Connecting) {
                    startUserTurn()
                }
            }

            is KiwiSessionEvent.AudioOutput -> {
                pendingPlaybackChunks.incrementAndGet()
                playbackQueue.trySend(event.pcm)
                val current = _pipeline.value
                if (current is PipelineState.Processing) {
                    KLog.i(TAG, "first audio chunk → Responding")
                    _pipeline.value = PipelineState.Responding(
                        userTranscript = current.userTranscript,
                        kiwiTranscript = "",
                    )
                }
            }

            is KiwiSessionEvent.InputTranscript -> appendInputTranscript(event.text)
            is KiwiSessionEvent.OutputTranscript -> appendOutputTranscript(event.text)

            is KiwiSessionEvent.SceneSet -> {
                KLog.i(TAG, "scene.set → ${event.scene::class.simpleName}")
                host.onSceneSet(event.scene)
            }

            is KiwiSessionEvent.DeviceCommand -> {
                KLog.i(TAG, "device_command: ${event.command} pkg=${event.packageName}")
                host.onDeviceCommand(event)
            }

            KiwiSessionEvent.ResponseEnd -> {
                if (host.shouldCloseAfterResponse()) {
                    KLog.i(TAG, "response.end on playback scene → drain + close")
                    waitForAudioAndCloseConversation()
                } else {
                    KLog.i(TAG, "response.end → drain → next turn")
                    waitForAudioAndStartNextTurn()
                }
            }

            is KiwiSessionEvent.Closed -> {
                val current = _pipeline.value
                when {
                    current is PipelineState.Error -> Unit
                    current is PipelineState.Idle -> Unit
                    event.code == 1000 -> {
                        KLog.i(TAG, "WS closed cleanly by server (reason=${event.reason})")
                        internalClose()
                    }
                    else -> {
                        val reason = event.reason.takeIf { it.isNotBlank() }
                        val msg = if (reason != null) {
                            "Sesión cerrada (code=${event.code}, ${reason})"
                        } else {
                            "Sesión cerrada (code=${event.code})"
                        }
                        _pipeline.value = PipelineState.Error(msg)
                        cleanup()
                    }
                }
            }

            is KiwiSessionEvent.Error -> {
                if (
                    event.transient &&
                    !sessionReady &&
                    connectRetryAttempt < MAX_CONNECT_ATTEMPTS - 1
                ) {
                    connectRetryAttempt += 1
                    KLog.i(
                        TAG,
                        "transient connect failure (${event.message}); " +
                            "retry $connectRetryAttempt/${MAX_CONNECT_ATTEMPTS - 1}",
                    )
                    cleanup()
                    _pipeline.value = PipelineState.Reconnecting(
                        attempt = connectRetryAttempt,
                        maxAttempts = MAX_CONNECT_ATTEMPTS - 1,
                    )
                    val delayMs = if (connectRetryAttempt == 1) 1_000L else 3_000L
                    scope.launch {
                        delay(delayMs)
                        if (_pipeline.value is PipelineState.Reconnecting) {
                            connect()
                        }
                    }
                } else {
                    _pipeline.value = PipelineState.Error(event.message)
                    cleanup()
                    connectRetryAttempt = 0
                    sessionReady = false
                }
            }
        }
    }

    private fun appendInputTranscript(chunk: String) {
        val updated = when (val current = _pipeline.value) {
            is PipelineState.Processing ->
                current.copy(userTranscript = current.userTranscript + chunk)
            is PipelineState.Responding ->
                current.copy(userTranscript = current.userTranscript + chunk)
            else -> return
        }
        _pipeline.value = updated
    }

    private fun appendOutputTranscript(chunk: String) {
        val updated = when (val current = _pipeline.value) {
            is PipelineState.Responding ->
                current.copy(kiwiTranscript = current.kiwiTranscript + chunk)
            is PipelineState.Processing ->
                PipelineState.Responding(
                    userTranscript = current.userTranscript,
                    kiwiTranscript = chunk,
                )
            else -> return
        }
        _pipeline.value = updated
    }

    private fun waitForAudioAndStartNextTurn() {
        if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return
        scope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)
            if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return@launch
            startUserTurn()
        }
    }

    private fun waitForAudioAndCloseConversation() {
        if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return
        scope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)
            if (_pipeline.value is PipelineState.Idle) return@launch
            internalClose()
        }
    }

    /** Cierre iniciado por el motor: teardown + avisa al host. */
    private fun internalClose() {
        close()
        host.onClosed()
    }

    private fun cleanup() {
        capture.stop()
        playback.stop()
        session?.close()
        session = null
        audioFocus.release()
    }

    private companion object {
        const val TAG = "ConversationEngine"
        const val SILENCE_END_OF_TURN_MS = 1_200L
        const val NO_SPEECH_TIMEOUT_MS = 6_000L
        const val MAX_CONNECT_ATTEMPTS = 3
    }
}
