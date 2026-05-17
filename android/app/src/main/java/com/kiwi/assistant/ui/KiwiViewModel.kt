package com.kiwi.assistant.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.AudioCaptureManager
import com.kiwi.assistant.audio.AudioPlaybackManager
import com.kiwi.assistant.audio.SpeechActivityDetector
import com.kiwi.assistant.alarm.AlarmScheduler
import com.kiwi.assistant.audio.VoskKeywordListener
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.network.HomeStatePoller
import com.kiwi.assistant.network.KiwiSession
import com.kiwi.assistant.network.KiwiSessionEvent
import com.kiwi.assistant.network.TodoApi
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    // Histórico de scenes para que el back arrow vuelva a la anterior
    // (Home → TodoList → Calendar → back → TodoList → back → Home).
    // Solo contiene scenes "pop-eables"; Idle nunca entra al stack
    // (Home es siempre el suelo). Mutaciones serializadas por el hilo
    // Main donde corren los call sites.
    private val sceneStack: ArrayDeque<Scene> = ArrayDeque()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /**
     * Navega a [target] manteniendo histórico. Si la escena actual es
     * Idle o del mismo tipo que [target] (re-render con datos nuevos)
     * NO se mete en el stack — evita basura como pulsar 3 veces back
     * para salir de una TodoList que se actualizó 3 veces.
     */
    private fun enterScene(target: Scene) {
        val current = _scene.value
        val sameType = current::class == target::class
        // Ambient se trata como Idle a efectos del stack — es una
        // deriva pasiva, no una "página" a la que el back debería
        // volver. Sin esto, pulsar back desde Calendar te llevaría
        // a la vista de pared en vez de a Home, lo cual no tiene
        // sentido.
        val pushable = current !is Scene.Idle && current !== Scene.Ambient
        if (pushable && !sameType) {
            sceneStack.addLast(current)
        }
        _scene.value = target
        _canGoBack.value = sceneStack.isNotEmpty()
    }

    /** Vuelve a la escena anterior, o a Idle si no había. */
    private fun popScene() {
        val previous = sceneStack.removeLastOrNull()
        _scene.value = previous ?: Scene.Idle
        _canGoBack.value = sceneStack.isNotEmpty()
    }

    /** Salida limpia: vacía el stack y vuelve a Home. */
    private fun resetToHome() {
        sceneStack.clear()
        _scene.value = Scene.Idle
        _canGoBack.value = false
    }

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

    private val alarmScheduler = AlarmScheduler(application.applicationContext)

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
        // Reconcile AlarmManager schedule with the authoritative list
        // every refresh — covers the boot-up case (first poll re-arms
        // alarms after a reboot) and any change made by voice while
        // the app was offline. Defensive: AlarmManager calls can
        // throw SecurityException in some Android 14+ scenarios; let
        // the snapshot stick aunque el scheduler falle.
        runCatching { alarmScheduler.sync(snapshot.alarms) }
            .onFailure { e ->
                KLog.w(
                    TAG,
                    "alarmScheduler.sync from snapshot failed: " +
                        "${e::class.simpleName}: ${e.message}",
                )
            }
    }

    /** On-demand refresh — fire-and-forget, tolerates transient failures. */
    private fun triggerHomeRefresh() {
        viewModelScope.launch(Dispatchers.IO) { refreshHomeSnapshot() }
    }

    /**
     * Auto-close info-display scenes (Calendar, VideoList, PlaylistList,
     * TodoList) after [SCENE_AUTO_CLOSE_MS] of inactivity, returning the
     * tablet to the home dashboard. We use [collectLatest] so any change
     * to scene OR pipeline cancels the pending timer cleanly: a fresh
     * scene push, the user re-engaging Kiwi, or pressing X all reset
     * the countdown. Passive consumption scenes (VideoPlayer,
     * BrowseYouTube, NowPlaying) are explicitly excluded — the user is
     * still using them even if no input arrives.
     */
    /**
     * Banner-style "el evento X empieza pronto" overlay. Null cuando
     * no hay aviso activo. La UI lo renderiza por encima de la escena
     * actual sin reemplazarla.
     */
    private val _eventSoonBanner = MutableStateFlow<EventSoonBanner?>(null)
    val eventSoonBanner: StateFlow<EventSoonBanner?> = _eventSoonBanner.asStateFlow()

    /**
     * Eventos para los que ya hemos disparado el banner en este
     * proceso. Reseteo en init / al cruzar medianoche para que un
     * mismo evento no pite dos veces seguidas, pero sí pueda volver a
     * pitar si la app se reinicia.
     */
    private val notifiedEventKeys: MutableSet<String> = mutableSetOf()
    private var notifiedEventDay: Int = -1

    /**
     * Tick cada 30 s revisando ``homeSnapshot.eventsToday``: si algún
     * evento timed empieza dentro de los próximos
     * [EVENT_SOON_LEAD_MS] y aún no lo hemos avisado, dispara el
     * banner. Auto-cierra el banner tras
     * [EVENT_SOON_DISPLAY_MS] o cuando el usuario pulsa X.
     */
    private val eventSoonJob: Job = viewModelScope.launch {
        while (true) {
            try {
                checkUpcomingEvents()
            } catch (e: Exception) {
                KLog.w(TAG, "eventSoon tick failed: ${e::class.simpleName}: ${e.message}")
            }
            delay(EVENT_SOON_TICK_MS)
        }
    }

    private fun checkUpcomingEvents() {
        // Reset diario del set de notificados — sin esto, un evento
        // que se posponga ("oh, mañana hay reunión a las mismas") no
        // pitaría porque su key (title|starts_at) seguiría aquí.
        val today = LocalDate.now().toEpochDay().toInt()
        if (today != notifiedEventDay) {
            notifiedEventKeys.clear()
            notifiedEventDay = today
        }

        val events = _homeSnapshot.value?.eventsToday ?: return
        val nowMs = System.currentTimeMillis()
        for (event in events) {
            if (event.allDay) continue
            val startMs = runCatching {
                java.time.OffsetDateTime.parse(event.startsAt).toInstant().toEpochMilli()
            }.getOrNull() ?: continue
            val deltaMs = startMs - nowMs
            if (deltaMs !in 0..EVENT_SOON_LEAD_MS) continue
            val key = "${event.title}|${event.startsAt}"
            if (key in notifiedEventKeys) continue
            notifiedEventKeys.add(key)
            _eventSoonBanner.value = EventSoonBanner(
                title = event.title,
                startsAt = event.startsAt,
                location = event.location,
            )
            // Auto-cierre del banner tras X segundos para no
            // monopolizar la pantalla.
            viewModelScope.launch {
                delay(EVENT_SOON_DISPLAY_MS)
                if (_eventSoonBanner.value?.startsAt == event.startsAt) {
                    _eventSoonBanner.value = null
                }
            }
            // Sólo un evento por tick — si hay dos casi simultáneos,
            // el segundo aparece en el próximo tick.
            return
        }
    }

    /** El usuario cierra el banner manualmente (X). */
    fun onDismissEventBanner() {
        _eventSoonBanner.value = null
    }

    private val sceneAutoCloseJob: Job = viewModelScope.launch {
        combine(scene, pipeline) { s, p -> s to p }
            .collectLatest { (currentScene, currentPipeline) ->
                if (
                    isAutoCloseable(currentScene) &&
                    currentPipeline is PipelineState.Idle
                ) {
                    delay(SCENE_AUTO_CLOSE_MS)
                    // Re-check on the main thread before yanking: a
                    // race where a new scene push lands during the
                    // delay would otherwise be clobbered.
                    if (
                        _scene.value === currentScene &&
                        _pipeline.value is PipelineState.Idle
                    ) {
                        KLog.i(TAG, "auto-close: ${currentScene::class.simpleName} → home")
                        resetToHome()
                        triggerHomeRefresh()
                    }
                }
            }
    }

    private fun isAutoCloseable(scene: Scene): Boolean = when (scene) {
        is Scene.Calendar,
        is Scene.VideoList,
        is Scene.PlaylistList,
        is Scene.TodoList,
        is Scene.AlarmList,
        is Scene.ShoppingList,
        is Scene.UsageStats,
        -> true
        is Scene.VideoPlayer,
        is Scene.BrowseYouTube,
        is Scene.NowPlaying,
        is Scene.Timer,
        is Scene.AlarmRinging,  // sólo sale al pulsar Apagar / Posponer.
        Scene.Idle,
        Scene.Ambient,  // sale solo con tap del usuario o wake word.
        -> false
    }

    /**
     * "Vista de pared" — tras [AMBIENT_IDLE_DELAY_MS] en Idle sin que
     * el usuario toque ni hable, el tablet entra a [Scene.Ambient]
     * (reloj gigante + una sola pieza de info). El listener combina
     * scene + pipeline: solo arma el timer cuando ambos están en Idle.
     * Al salir de Idle (toque, wake word, scene push del backend) el
     * timer se cancela; al volver, se rearma.
     */
    private var ambientTimerJob: Job? = null
    private val ambientArmJob: Job = viewModelScope.launch {
        combine(scene, pipeline) { s, p ->
            s is Scene.Idle && p is PipelineState.Idle
        }
            .distinctUntilChanged()
            .collect { isIdle ->
                if (isIdle) scheduleAmbient() else cancelAmbient()
            }
    }

    /**
     * Si estamos en Ambient (vista de pared) y el pipeline arranca
     * (wake word, tap del usuario, scene push del backend), salimos
     * de Ambient automáticamente — el reloj gigante no debe pisar
     * la conversación. Tras cerrar la conversación el usuario
     * vuelve a Home, no a la vista de pared (el timer la re-armará
     * tras otros 3 min de inactividad).
     */
    private val ambientExitOnActivityJob: Job = viewModelScope.launch {
        pipeline.collect { p ->
            if (p !is PipelineState.Idle && _scene.value === Scene.Ambient) {
                _scene.value = Scene.Idle
            }
        }
    }

    private fun scheduleAmbient() {
        ambientTimerJob?.cancel()
        ambientTimerJob = viewModelScope.launch {
            delay(AMBIENT_IDLE_DELAY_MS)
            // Re-check: por si justo en este último delay el estado
            // cambió antes de que cancelAmbient corra (race con el
            // collect del flow combine).
            if (_scene.value is Scene.Idle && _pipeline.value is PipelineState.Idle) {
                _scene.value = Scene.Ambient
            }
        }
    }

    private fun cancelAmbient() {
        ambientTimerJob?.cancel()
        ambientTimerJob = null
    }

    /**
     * Tap-anywhere desde [Scene.Ambient] → vuelve al home normal y
     * re-arma el timer. La wake word saca solo (cambio de pipeline
     * dispara el cancelAmbient del flow).
     */
    fun exitAmbient() {
        if (_scene.value === Scene.Ambient) {
            _scene.value = Scene.Idle
            triggerHomeRefresh()
        }
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
        enterScene(Scene.TodoList(items = items))
    }

    /**
     * Abre la lista de despertadores desde el chip de la home. Sin
     * red: la lista la tenemos cacheada en homeSnapshot porque viene
     * con cada /api/home. Si por algo el snapshot está vacío,
     * abrimos la escena con lista vacía — el chip ni siquiera se
     * pinta entonces, así que llegar aquí en ese estado sería raro.
     */
    fun onOpenAlarmList() {
        val items = _homeSnapshot.value?.alarms.orEmpty()
        enterScene(Scene.AlarmList(items = items))
    }

    /**
     * Abre la pantalla de uso/costes desde la home (chip "Uso").
     * Hace el fetch a /api/stats en background y empuja
     * [Scene.UsageStats]; si la red falla, empuja una escena con
     * todo a cero — al menos el usuario ve que ha aterrizado en la
     * pantalla aunque sin datos.
     */
    fun onOpenUsageStats(period: String = "today") {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = todoApi.fetchUsageStats(period)
                ?: Scene.UsageStats(
                    period = period,
                    conversationCount = 0,
                    turnCount = 0,
                    audioInSeconds = 0.0,
                    audioOutSeconds = 0.0,
                    audioTotalSeconds = 0.0,
                    estimatedCostEur = 0.0,
                    topTools = emptyList(),
                )
            enterScene(stats)
        }
    }

    /**
     * Abre la escena de calendario desde la home con la agenda de hoy
     * que ya tenemos cacheada en homeSnapshot. Sin red — la voz puede
     * pedir periodos más amplios (esta semana / 7 días) por sí sola.
     */
    fun onOpenCalendar() {
        val events = _homeSnapshot.value?.eventsToday.orEmpty()
        enterScene(Scene.Calendar(period = "today", events = events))
    }

    /**
     * Tap en la barra "Suena ahora" del home → pantalla NowPlaying
     * grande. Solo tiene sentido cuando ya hay música; en otro caso
     * no se pinta la barra (no hay tap). El backend no expone HTTP
     * para el estado completo de Spotify (es un voice tool) así que
     * reconstruimos lo que podemos del chip: title, artist, carátula.
     * Album / duración / progreso quedan vacíos y el composable los
     * oculta (durationMs == 0 → sin progress bar).
     */
    fun onOpenNowPlaying() {
        val chip = _homeSnapshot.value?.nowPlaying ?: return
        enterScene(
            Scene.NowPlaying(
                title = chip.title,
                artist = chip.artist,
                album = "",
                albumArtUrl = chip.albumArtUrl,
                isPlaying = true,
                durationMs = 0L,
                progressMs = 0L,
            ),
        )
    }

    /**
     * Abre la lista de la compra desde la quick-actions row. Fetch
     * fresco a /api/shopping (no está en el snapshot del home) en
     * background; mientras tanto entramos a la escena vacía para que
     * la transición sea inmediata. Si la red falla, queda en vacío
     * — un "tira otra vez" lo arregla.
     */
    fun onOpenShoppingList() {
        // Empty scene first, then replace with fetched items.
        enterScene(Scene.ShoppingList(items = emptyList()))
        viewModelScope.launch(Dispatchers.IO) {
            val items = todoApi.fetchShoppingList()?.map {
                ShoppingItem(id = it.id, text = it.text, completed = it.completed)
            } ?: return@launch
            if (_scene.value is Scene.ShoppingList) {
                enterScene(Scene.ShoppingList(items = items))
            }
        }
    }

    fun onTimerDismiss() {
        viewModelScope.launch(Dispatchers.IO) { todoApi.cancelTimer() }
        if (_scene.value is Scene.Timer) {
            popScene()
        }
    }

    /**
     * Called by [MainActivity] when AlarmManager's broadcast receiver
     * launched us with an alarm-ring intent. Flips the scene to
     * AlarmRinging — the composable starts the alarm tone there.
     */
    fun onAlarmRing(alarmId: String, label: String, firesAtMs: Long) {
        KLog.i(TAG, "alarm ring: id=$alarmId label=$label")
        enterScene(
            Scene.AlarmRinging(
                alarmId = alarmId,
                label = label,
                firesAtMs = firesAtMs,
            ),
        )
    }

    /** Apagar from the AlarmRingingScene: drop the alarm + go home. */
    fun onAlarmDismiss(alarmId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            todoApi.dismissAlarm(alarmId)
            refreshHomeSnapshot()
        }
        if (_scene.value is Scene.AlarmRinging) {
            popScene()
        }
    }

    /**
     * Posponer from the AlarmRingingScene: push the alarm forward by
     * [minutes] on the backend; the next snapshot reschedules it via
     * AlarmManager. Goes home immediately so the alarm tone stops.
     */
    fun onAlarmSnooze(alarmId: String, minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            todoApi.snoozeAlarm(alarmId, minutes)
            refreshHomeSnapshot()
        }
        if (_scene.value is Scene.AlarmRinging) {
            popScene()
        }
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
                // Mismo tipo de escena → enterScene replace sin meter
                // basura en el stack (la TodoList previa no es una
                // "página anterior", es el mismo screen actualizado).
                enterScene(Scene.TodoList(items = updated))
            }
            refreshHomeSnapshot()
        }
    }

    /**
     * Tap-to-toggle para la escena de la compra. Mismo modelo que
     * [onTodoTap]: pendiente → comprado, comprado → eliminado.
     */
    fun onShoppingTap(item: ShoppingItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val dtoList = if (!item.completed) {
                todoApi.completeShopping(item.id)
            } else {
                todoApi.removeShopping(item.id)
            } ?: return@launch
            val mapped = dtoList.map {
                ShoppingItem(id = it.id, text = it.text, completed = it.completed)
            }
            if (_scene.value is Scene.ShoppingList) {
                enterScene(Scene.ShoppingList(items = mapped))
            }
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
    // Wake-word listener (Vosk ASR offline en español, "hola kiwi" /
    // "alexa" / etc.). Posee el micro mientras la app está Idle y lo
    // libera al disparar el wake word o cuando el usuario toca para
    // arrancar manualmente. Sólo un AudioRecord puede estar activo
    // por proceso para la misma fuente → el start/stop dance tiene
    // que ser disciplinado, ver openSession() / endSession().
    private val wakeWordListener = VoskKeywordListener(application)
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

    /**
     * Tracks how many times the current ``openSession`` flow has
     * retried a failed connect. Reset on successful ``session.ready``
     * and when we surface a hard error.
     */
    private var connectRetryAttempt = 0

    /** Set true on session.ready so we know whether a later failure
     *  is "WS never opened" (retry) vs "WS dropped mid-conversation"
     *  (don't retry, the conversation is gone anyway). */
    private var sessionReady = false

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
            // Same idea while we're waiting between auto-retries —
            // the scheduled coroutine will retry on its own. Long-press
            // / X cancels if the user really wants out.
            is PipelineState.Reconnecting -> Unit
            PipelineState.Listening -> endUserTurn()
            is PipelineState.Processing,
            is PipelineState.Responding,
            -> Unit
            is PipelineState.Error -> {
                _pipeline.value = PipelineState.Idle
                // Wipe whatever scene a previous turn pushed too —
                // recovering from an error returns the tablet to the
                // clock, not to a stale calendar/now-playing view.
                resetToHome()
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
    /**
     * Back arrow del top-bar global. Vuelve a la escena anterior
     * (stack pop). Llamado desde [KiwiScreen] cuando hay histórico.
     * Si el stack queda vacío termina en Home; el caller decide qué
     * hacer con la conversación activa.
     */
    fun onBack() {
        if (_scene.value !is Scene.Idle) {
            popScene()
            triggerHomeRefresh()
        }
    }

    fun onExitScene() {
        if (_scene.value !is Scene.Idle) {
            // Back arrow propio de WebViews — pop al histórico, no
            // reset directo. Si stack está vacío vuelve a Idle igual.
            popScene()
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

        sessionReady = false
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
                sessionReady = true
                connectRetryAttempt = 0
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
                // enterScene maneja smart-push (mismo tipo = replace
                // sin meter al stack), así que un tool que actualiza
                // TodoList por voz no crea entrada de back redundante.
                enterScene(event.scene)
                // Voice-driven alarm tools push the full updated
                // alarm list as Scene.AlarmList — reconcile the system
                // scheduler immediately so a "ponme un despertador en
                // 2 min" doesn't have to wait for the next /api/home
                // poll to actually arm. Defensive try-catch: si
                // setAlarmClock se queja (p.ej. SecurityException en
                // Android 14+ sin permiso), preferimos enseñar la
                // escena con la alarma "registrada" antes que matar
                // el proceso a media conversación.
                if (event.scene is Scene.AlarmList) {
                    runCatching { alarmScheduler.sync(event.scene.items) }
                        .onFailure { e ->
                            KLog.w(
                                TAG,
                                "alarmScheduler.sync from scene failed: " +
                                    "${e::class.simpleName}: ${e.message}",
                            )
                        }
                }
            }

            is KiwiSessionEvent.DeviceCommand -> {
                KLog.i(
                    TAG,
                    "device_command: ${event.command} pkg=${event.packageName}",
                )
                handleDeviceCommand(event)
            }

            KiwiSessionEvent.ResponseEnd -> {
                if (isPassiveConsumptionScene(_scene.value)) {
                    // The user just told Kiwi to play something
                    // (video or song). Once Kiwi finishes saying
                    // "ahora reproduciendo X" there is no point
                    // re-opening the mic for another turn.
                    KLog.i(TAG, "response.end on playback scene → drain + close")
                    waitForAudioAndCloseConversation()
                } else {
                    // Despedidas explícitas las decide Gemini llamando
                    // a la tool end_conversation; el backend cierra el
                    // WS tras este turno y aquí caerá el Closed event
                    // que ya manejamos. No hay heurísticas de keyword
                    // en el cliente — todo decisión generativa.
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
                        // Cierre limpio iniciado por el server
                        // (típicamente la tool end_conversation):
                        // volvemos a Idle como en cualquier cierre
                        // normal, sin pantalla de error.
                        KLog.i(
                            TAG,
                            "WS closed cleanly by server (reason=${event.reason})",
                        )
                        closeConversation(resetScene = false)
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
                    // Pre-handshake transient (DNS, EBADF post-Doze, …):
                    // tear down the dead session and schedule a retry.
                    // Mid-conversation drops fall through to Error
                    // because the upstream Gemini turn would be lost
                    // anyway.
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
                    val attempt = connectRetryAttempt
                    val delayMs = if (attempt == 1) 1_000L else 3_000L
                    viewModelScope.launch {
                        delay(delayMs)
                        // The user may have cancelled (long-press, X)
                        // during the wait — only retry if we're still
                        // in Reconnecting.
                        if (_pipeline.value is PipelineState.Reconnecting) {
                            openSession()
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
     * Ejecuta un comando enviado por una tool (despertar Spotify,
     * subir volumen, etc.). Patrón Waze: lanza un Intent al app
     * target, espera unos segundos para que el SO acabe el
     * cambio, y vuelve automáticamente a Kiwi en foreground para
     * que la conversación siga viva.
     *
     * Defensivo: si el paquete no está instalado o startActivity
     * falla, log + sigue. La conversación no se rompe; en el peor
     * caso Kiwi le dirá al usuario que abra la app a mano.
     */
    private fun handleDeviceCommand(event: KiwiSessionEvent.DeviceCommand) {
        when (event.command) {
            "open_app_then_return" -> {
                val pkg = event.packageName ?: return
                openAppAndReturnToKiwi(pkg)
            }
            "set_volume" -> applyVolume(level = event.level, delta = event.delta)
            else -> KLog.w(TAG, "unknown device_command: ${event.command}")
        }
    }

    /**
     * Ajusta el volumen multimedia. `level` (0-100) gana sobre
     * `delta` (-100..+100) si ambos vienen. Sin nada útil, no-op.
     * El rango user-facing 0-100 se mapea a STREAM_MUSIC del SO,
     * que típicamente va 0..maxStreamVolume (15 en Pixel) — así
     * el usuario no tiene que pensar en "puntos del slider del SO"
     * sino en porcentaje.
     */
    private fun applyVolume(level: Int?, delta: Int?) {
        val ctx = getApplication<Application>().applicationContext
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            KLog.w(TAG, "AudioManager unavailable; volume command ignored")
            return
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) {
            KLog.w(TAG, "STREAM_MUSIC max volume <= 0; ignoring")
            return
        }
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        val targetSteps: Int = when {
            level != null -> ((level.coerceIn(0, 100) * max) + 50) / 100
            delta != null -> {
                val currentPct = (current * 100 + max / 2) / max
                val newPct = (currentPct + delta).coerceIn(0, 100)
                ((newPct * max) + 50) / 100
            }
            else -> return
        }
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetSteps,
                AudioManager.FLAG_SHOW_UI,
            )
        }.onFailure {
            KLog.w(TAG, "setStreamVolume($targetSteps) failed: ${it.message}")
        }
        KLog.i(
            TAG,
            "volume: ${current}/${max} → ${targetSteps}/${max} " +
                "(level=$level, delta=$delta)",
        )
    }

    private fun openAppAndReturnToKiwi(packageName: String) {
        val ctx = getApplication<Application>().applicationContext
        val pm = ctx.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            KLog.w(TAG, "package $packageName not installed; cannot launch")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(launchIntent) }.onFailure {
            KLog.w(TAG, "startActivity($packageName) failed: ${it.message}")
            return
        }
        // Tras un breve delay, volvemos a Kiwi en foreground para
        // que la conversación (que sigue activa) no se quede
        // atrapada detrás de la app que acabamos de lanzar. El
        // backend está esperando ~4 s para reintentar el play, así
        // que con 2 s nos da tiempo a que Spotify se registre como
        // Connect device y aún terminamos antes del retry.
        viewModelScope.launch {
            delay(RETURN_TO_KIWI_DELAY_MS)
            val kiwiIntent = pm.getLaunchIntentForPackage(ctx.packageName)
            kiwiIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            runCatching { kiwiIntent?.let { ctx.startActivity(it) } }
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
        connectRetryAttempt = 0
        sessionReady = false
        _pipeline.value = PipelineState.Idle
        // The close-conversation X buttons keep the active scene
        // (calendar / now-playing / …) so the user can keep reading
        // what was on screen. Long-press wipes scene too — the
        // gesture is the "go home" affordance.
        if (resetScene) resetToHome()
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
        sceneAutoCloseJob.cancel()
        eventSoonJob.cancel()
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
        // streams PCM to Gemini Live continuously, así que cada
        // segundo de mic-abierto-y-nadie-hablando quema tokens de
        // input audio. 6 s deja margen para "lo voy a pensar un
        // momento" pero corta rápido cuando ya acabaste; la detección
        // de despedida de Kiwi (looksLikeGoodbye) cubre el caso
        // explícito "nada más" cerrando antes incluso del timeout.
        const val NO_SPEECH_TIMEOUT_MS = 6_000L

        // Total handshake attempts before we surface PipelineState.Error.
        // First attempt + 2 retries with 1s/3s backoff covers the
        // typical post-Doze stale-socket case (~3 s for the WiFi/4G
        // stack to stabilise) without making real outages drag on.
        const val MAX_CONNECT_ATTEMPTS = 3

        // Tras un device_command tipo "open_app_then_return" lanzamos
        // la app target con un Intent y, transcurrido este delay,
        // volvemos a Kiwi en foreground. El backend que disparó el
        // comando suele esperar ~4 s antes de reintentar la acción
        // (p.ej. spotify_play tras despertar Spotify); 2 s da
        // tiempo a que la app target se inicialice + se registre
        // (como Connect device en Spotify) sin alargar la
        // experiencia del usuario.
        const val RETURN_TO_KIWI_DELAY_MS = 2_000L

        // Auto-cierre de escenas informativas (Calendar / VideoList /
        // PlaylistList / TodoList / AlarmList / ShoppingList /
        // UsageStats): si el usuario las deja en pantalla sin tocar y
        // sin volver a hablar con Kiwi durante este tiempo, el tablet
        // vuelve a la home automáticamente.
        const val SCENE_AUTO_CLOSE_MS = 30_000L

        // "Vista de pared": tras este tiempo en Idle sin tocar, el
        // tablet pasa a Ambient (reloj gigante + una sola pieza de
        // info). 3 min equilibra "no quita la home si estás cerca"
        // con "no tarda demasiado si dejas el tablet quieto".
        const val AMBIENT_IDLE_DELAY_MS = 3L * 60_000L

        // Pre-aviso de evento de calendario: ventana en la que se
        // dispara el banner antes del start time. 5 min cubre el
        // típico "ya casi" sin saturar al usuario con alertas.
        const val EVENT_SOON_LEAD_MS = 5L * 60_000L
        // Cada cuánto revisamos los eventos próximos. Más fino que el
        // refresh de homeSnapshot (5 min), porque necesitamos pillar
        // la transición a "<5 min" antes de que pase.
        const val EVENT_SOON_TICK_MS = 30_000L
        // Cuánto deja el banner visible una vez disparado.
        const val EVENT_SOON_DISPLAY_MS = 30_000L
    }
}
