package com.kiwi.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioPlaybackManager
import com.kiwi.assistant.audio.SpeechActivityDetector
import com.kiwi.assistant.audio.WakeWordListener
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.network.HomeStatePoller
import com.kiwi.assistant.network.KiwiSession
import com.kiwi.assistant.network.KiwiSessionEvent
import com.kiwi.assistant.network.TodoApi
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

    private val _pipeline = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    // What's currently on the canvas. Defaults to the clock; later
    // fases (Calendar, NowPlaying, VideoPlayer, BrowseYT) will mutate
    // this from tool callbacks. It's exposed independently of
    // [pipeline] so a Kiwi conversation can run as an overlay on top
    // of whatever the user is looking at — the tablet keeps showing
    // the calendar / album art / video while Gemini answers.
    private val _scene = MutableStateFlow<Scene>(Scene.Idle)
    val scene: StateFlow<Scene> = _scene.asStateFlow()

    // Snapshot rendered by the Idle/Home dashboard. Null until the
    // first /api/home fetch resolves, in which case [HomeScene] falls
    // back to a bare clock.
    private val _homeSnapshot = MutableStateFlow<HomeSnapshot?>(null)
    val homeSnapshot: StateFlow<HomeSnapshot?> = _homeSnapshot.asStateFlow()

    private val homePoller = HomeStatePoller(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
    )

    private val todoApi = TodoApi(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
    )

    /**
     * Background coroutine that refreshes [homeSnapshot] every
     * [HOME_REFRESH_INTERVAL_MS]. Started in init and lives for the
     * ViewModel's lifetime; the IO dispatcher keeps the network call
     * off the main thread. Failures keep the previous snapshot on
     * screen — the Home scene degrades to a bare clock if there's
     * never been a successful fetch.
     */
    private val homePollerJob: Job = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            refreshHomeSnapshot()
            delay(HOME_REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun refreshHomeSnapshot() {
        val snapshot = homePoller.fetchOnce() ?: return
        _homeSnapshot.value = snapshot
    }

    /** On-demand refresh — fire-and-forget, tolerates transient failures. */
    private fun triggerHomeRefresh() {
        viewModelScope.launch(Dispatchers.IO) { refreshHomeSnapshot() }
    }

    /**
     * Tap-to-toggle for a TODO from the [Scene.TodoList] surface.
     *
     * Pending → mark completed. Completed → remove (so a second tap on
     * a struck-through item drops it from the list). Both operations
     * hit the dev-token-gated REST endpoints; on success we update
     * the on-screen scene with the server-returned list and refresh
     * the home snapshot. On failure we silently keep the previous
     * state — there's a logged warning in [TodoApi].
     */
    /**
     * Open the dedicated TodoList scene, populated from the cached
     * home snapshot. Called from the home dashboard's TODOs card so
     * a tap drills into the full list without going through Gemini.
     * Empty snapshot → empty TodoList ("Nada apuntado.").
     */
    fun onOpenTodoList() {
        val items = _homeSnapshot.value?.todos.orEmpty()
        _scene.value = Scene.TodoList(items = items)
    }

    fun onTodoTap(item: TodoItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = if (!item.completed) {
                todoApi.complete(item.id)
            } else {
                todoApi.remove(item.id)
            } ?: return@launch
            // Push the new list onto the active scene so the row
            // updates without waiting for the next /api/home cycle.
            // Done on the IO dispatcher → switching back to main is
            // not required for StateFlow.value, but we trigger the
            // home refresh which needs to land too.
            if (_scene.value is Scene.TodoList) {
                _scene.value = Scene.TodoList(items = updated)
            }
            refreshHomeSnapshot()
        }
    }

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
    // Wake-word listener (openWakeWord, "hey jarvis"). Owns the mic
    // continuously while the app is in Idle, releases it for the
    // session capture path the moment a wake word lands or the user
    // taps to start manually. Only one AudioRecord can be active per
    // process for the same source, so the start/stop dance has to be
    // disciplined — see openSession() / endSession() for the choreography.
    private val wakeWordListener = WakeWordListener(application)
    private var session: KiwiSession? = null

    /**
     * Coroutine that auto-closes the conversation if the user goes
     * silent for [NO_SPEECH_TIMEOUT_MS] in [PipelineState.Listening].
     * Without it, Kiwi would keep streaming PCM to Gemini Live (and
     * burning input-audio tokens) for as long as the mic stays open.
     */
    private var noSpeechTimeoutJob: Job? = null

    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingPlaybackChunks = AtomicInteger(0)

    private val playbackWorker: Job = viewModelScope.launch(Dispatchers.IO) {
        for (chunk in playbackQueue) {
            try {
                playback.play(chunk)
            } catch (e: Exception) {
                KLog.w("KiwiViewModel", "playback chunk failed", e)
            } finally {
                pendingPlaybackChunks.decrementAndGet()
            }
        }
    }

    private var permissionGranted = false

    fun setMicrophonePermission(granted: Boolean) {
        val wasGranted = permissionGranted
        permissionGranted = granted
        // If permission has just been granted while we were sitting on
        // the clock, light up the wake-word listener so the user can
        // start talking right away. If it just got revoked, drop the
        // listener (it'd fail to read anyway).
        if (granted && !wasGranted && _pipeline.value is PipelineState.Idle) {
            startWakeWordListener()
        } else if (!granted && wasGranted) {
            wakeWordListener.stop()
        }
    }

    /**
     * Hand to call from the Activity once everything is wired and the
     * permission has been (re)checked, so the wake-word listener fires
     * up the first time the app comes to the foreground.
     */
    fun ensureWakeWordListening() {
        if (permissionGranted && _pipeline.value is PipelineState.Idle) {
            startWakeWordListener()
        }
    }

    /**
     * Drop the mic when the activity backgrounds. Without this the
     * AudioRecord stays open in the wake-word loop and burns the
     * battery while nothing's actually listening.
     */
    fun releaseMicForBackground() {
        wakeWordListener.stop()
    }

    private fun startWakeWordListener() {
        val started = wakeWordListener.start(viewModelScope) {
            KLog.i(TAG, "wake word fired → opening session")
            // Release the mic before openSession spins up its own
            // capture. start()/stop() on the listener is synchronous
            // enough that this is safe to do back-to-back here on the
            // main thread.
            wakeWordListener.stop()
            openSession()
        }
        if (!started) {
            KLog.w(TAG, "wake-word listener failed to start")
        }
    }

    fun onTap() {
        when (_pipeline.value) {
            PipelineState.Idle -> openSession()
            // While the WS handshake is in flight a tap would race with
            // the auto-start that fires on session.ready, so swallow it.
            PipelineState.Connecting -> Unit
            PipelineState.Listening -> endUserTurn()
            is PipelineState.Processing,
            is PipelineState.Responding,
            -> Unit
            is PipelineState.Error -> {
                _pipeline.value = PipelineState.Idle
                // Wipe whatever scene a previous turn pushed too —
                // recovering from an error returns the tablet to the
                // clock, not to a stale calendar/now-playing view.
                _scene.value = Scene.Idle
                // Re-arm the wake-word listener so the user can call
                // Kiwi again without tapping.
                startWakeWordListener()
            }
        }
    }

    /**
     * Exit the current scene back to the clock without touching any
     * active conversation. Used by scene-internal exit affordances
     * (e.g. the back arrow on [Scene.BrowseYouTube] when there's
     * nothing left to go back to in the WebView's history).
     *
     * The wake-word listener is re-armed in case there isn't an
     * active conversation either — symmetric with [closeConversation].
     */
    fun onExitScene() {
        if (_scene.value !is Scene.Idle) {
            _scene.value = Scene.Idle
            if (_pipeline.value is PipelineState.Idle) {
                startWakeWordListener()
            }
            triggerHomeRefresh()
        }
    }

    /**
     * Stop the conversation but keep whatever scene the tablet is
     * currently showing on top (calendar, now-playing, …). The user
     * can keep reading what's on screen with the wake-word listener
     * re-armed in the background.
     *
     * Used by the close (X) buttons. Safe to call in any state
     * (no-op when already Idle).
     */
    fun onCloseConversation() {
        if (_pipeline.value !is PipelineState.Idle) closeConversation(resetScene = false)
    }

    /**
     * Full reset back to the home (clock) scene. Wired to long-press
     * because the gesture is a strong "go home" affordance — it should
     * wipe both the conversation and any scene the tablet is showing.
     */
    fun onLongPress() {
        if (_pipeline.value !is PipelineState.Idle || _scene.value !is Scene.Idle) {
            closeConversation(resetScene = true)
        }
    }

    private fun openSession() {
        if (!permissionGranted) {
            _pipeline.value = PipelineState.Error("Concede permiso de micrófono para usar Kiwi.")
            return
        }
        if (BuildConfig.CLOUD_RUN_URL.isEmpty() || BuildConfig.KIWI_API_KEY.isEmpty()) {
            _pipeline.value = PipelineState.Error(
                "Configura CLOUD_RUN_URL y KIWI_API_KEY en local.properties.",
            )
            return
        }

        KLog.i(TAG, "openSession: connecting…")
        // Manual taps may arrive while the wake-word listener is still
        // holding the mic (e.g. user taps the screen instead of saying
        // "hey jarvis"). Always stop it before we open the session
        // capture, otherwise the second AudioRecord will fail to
        // initialise.
        wakeWordListener.stop()
        playback.start()
        val s = KiwiSession(BuildConfig.CLOUD_RUN_URL, BuildConfig.KIWI_API_KEY)
        session = s
        // Wait for session.ready before opening the mic — sending
        // activity_start while the WS is still mid-handshake would
        // queue it before session.start in OkHttp's outbound buffer
        // and the server would close the socket on us.
        _pipeline.value = PipelineState.Connecting
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
        KLog.i(TAG, "startUserTurn: sending activity_start")
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

    /**
     * Arm the no-speech auto-close. Re-armed on every [startUserTurn]
     * so the user always gets a fresh window after Kiwi finishes
     * answering. Cancelled the moment the user actually says
     * something (via [endUserTurn]) or the conversation closes.
     */
    private fun scheduleNoSpeechTimeout() {
        noSpeechTimeoutJob?.cancel()
        noSpeechTimeoutJob = viewModelScope.launch(Dispatchers.Main) {
            delay(NO_SPEECH_TIMEOUT_MS)
            // Only fire if we're still in Listening AND the user
            // hasn't crossed the speech threshold — otherwise the VAD
            // path is already going to handle the turn.
            if (_pipeline.value is PipelineState.Listening && !detector.userSpoke) {
                KLog.i(
                    TAG,
                    "no-speech timeout (${NO_SPEECH_TIMEOUT_MS}ms) — closing to stop billing",
                )
                session?.sendTurnCancel()
                closeConversation(resetScene = false)
            }
        }
    }

    private fun endUserTurn() {
        noSpeechTimeoutJob?.cancel()
        if (!detector.userSpoke) {
            // The user tapped to end without ever crossing the speech
            // threshold (mainstream voice agents discard these turns
            // rather than feeding silence to the model). Tell the
            // server to drop the upstream Gemini session for this
            // turn, then silently re-arm the mic.
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

    private fun handleEvent(event: KiwiSessionEvent) {
        when (event) {
            KiwiSessionEvent.SessionReady -> {
                KLog.i(TAG, "session.ready → auto-starting first turn")
                // Long-press during the handshake, or an error, may have
                // already closed the session — only auto-start if we're
                // still in the Connecting state we set in openSession.
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
                    // Carry the user transcript over so the UI keeps
                    // showing what Kiwi heard while it answers.
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
                _scene.value = event.scene
            }

            KiwiSessionEvent.ResponseEnd -> {
                if (isPassiveConsumptionScene(_scene.value)) {
                    // The user just told Kiwi to play something
                    // (video or song). Once Kiwi finishes saying
                    // "ahora reproduciendo X" there is no point
                    // re-opening the mic for another turn — we'd
                    // either pick up the playback as input or sit
                    // burning input tokens for 15 s until the
                    // no-speech timeout fires. Close cleanly so the
                    // HUD vanishes the moment Kiwi stops talking.
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
                _pipeline.value = PipelineState.Error(event.message)
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
            // Defensive: in theory we always see the first audio chunk
            // (which moves us to Responding) before any output transcript,
            // but if Gemini ever ships text first we still want to show it.
            is PipelineState.Processing ->
                PipelineState.Responding(
                    userTranscript = current.userTranscript,
                    kiwiTranscript = chunk,
                )
            else -> return
        }
        _pipeline.value = updated
    }

    /**
     * Wait for AudioTrack to actually finish playing the response, then
     * auto-start the next turn — otherwise we'd reopen the mic while
     * Kiwi is still mid-sentence and capture our own playback as input.
     * The 800 ms padding accounts for AudioTrack's internal buffer.
     */
    private fun waitForAudioAndStartNextTurn() {
        if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return
        viewModelScope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)
            // The user may have long-pressed during the drain; bail if
            // the session is already gone.
            if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return@launch
            startUserTurn()
        }
    }

    /**
     * Same drain-then-act dance as [waitForAudioAndStartNextTurn] but
     * closes the conversation instead of opening the next turn. Used
     * after a play-something tool fires so the HUD vanishes the moment
     * Kiwi finishes saying "ahora reproduciendo X" — keeping the mic
     * open while the user is consuming media is both pointless and
     * costly.
     */
    private fun waitForAudioAndCloseConversation() {
        if (_pipeline.value is PipelineState.Idle || _pipeline.value is PipelineState.Error) return
        viewModelScope.launch(Dispatchers.Main) {
            while (pendingPlaybackChunks.get() > 0) delay(50)
            delay(800)
            if (_pipeline.value is PipelineState.Idle) return@launch
            closeConversation(resetScene = false)
        }
    }

    /**
     * Whether the active scene is one where the user is consuming
     * media passively and we should NOT keep listening after Kiwi's
     * response. Add new scenes here when they become "media-playing"
     * destinations.
     */
    private fun isPassiveConsumptionScene(scene: Scene): Boolean = when (scene) {
        is Scene.VideoPlayer, is Scene.NowPlaying -> true
        else -> false
    }

    private fun closeConversation(resetScene: Boolean) {
        noSpeechTimeoutJob?.cancel()
        cleanup()
        _pipeline.value = PipelineState.Idle
        // The close-conversation X buttons keep the active scene
        // (calendar / now-playing / …) so the user can keep reading
        // what was on screen. Long-press wipes scene too — the
        // gesture is the "go home" affordance.
        if (resetScene) _scene.value = Scene.Idle
        // Re-arm the wake-word listener regardless: with the pipeline
        // closed, the user may still trigger Kiwi by saying the wake
        // word with the calendar (or whatever) still on screen.
        startWakeWordListener()
        // The conversation may have added/completed/removed a TODO or
        // bumped Spotify/Calendar state; if we land back on the Home
        // scene the user expects it fresh. Kicks off async — the
        // HomeScene keeps the previous snapshot until the new one
        // lands.
        if (_scene.value is Scene.Idle) triggerHomeRefresh()
    }

    private fun cleanup() {
        capture.stop()
        playback.stop()
        session?.close()
        session = null
    }

    override fun onCleared() {
        cleanup()
        wakeWordListener.stop()
        playbackQueue.close()
        playbackWorker.cancel()
        homePollerJob.cancel()
        if (detectorLazy.isInitialized()) {
            runCatching { detectorLazy.value.close() }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "KiwiViewModel"

        // How often we re-pull /api/home in the background. Five
        // minutes is enough to keep events / now-playing reasonably
        // current without burning Cloud Run requests on a tablet
        // that's mostly idle. After-conversation refresh handles the
        // "I just added a TODO" path on its own.
        const val HOME_REFRESH_INTERVAL_MS = 5L * 60_000L

        // How long the user has to be silent (after Silero stopped
        // returning isSpeech=true) before we auto-close the turn.
        // Silero already smooths over ~300 ms internally so the
        // perceived end-of-speech delay is ~300 ms longer than this.
        // 1200 ms here ⇒ feels like ~1.5 s of pause: long enough
        // that Kiwi doesn't cut the user off mid-thought when they
        // pause to phrase a longer query, short enough that the
        // back-and-forth still feels conversational.
        const val SILENCE_END_OF_TURN_MS = 1_200L

        // How long Listening can stay open WITHOUT any speech being
        // detected before we auto-close the conversation. Listening
        // streams PCM to Gemini Live continuously, so each second of
        // open-mic-no-one-talking burns input audio tokens. 15s gives
        // the user time to think after Kiwi answers; once they speak,
        // the regular VAD path takes over and this timer is cancelled.
        const val NO_SPEECH_TIMEOUT_MS = 15_000L
    }
}
