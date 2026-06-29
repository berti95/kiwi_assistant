package com.kiwi.assistant.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.a11y.KiwiAccessibilityService
import com.kiwi.assistant.alarm.AlarmScheduler
import com.kiwi.assistant.audio.VoskKeywordListener
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.network.HomeStatePoller
import com.kiwi.assistant.network.KiwiSessionEvent
import com.kiwi.assistant.network.SpotifyApi
import com.kiwi.assistant.network.SpotifyQueue
import com.kiwi.assistant.network.SpotifyState
import com.kiwi.assistant.network.SpotifyStateRepository
import com.kiwi.assistant.network.TodoApi
import com.kiwi.assistant.updater.AutoUpdater
import com.kiwi.assistant.voice.ConversationEngine
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
class KiwiViewModel(application: Application) :
    AndroidViewModel(application), ConversationEngine.Host {

    // Pipeline de conversación extraído a ConversationEngine para
    // poder reutilizarlo desde el foreground service (overlay, Fase
    // 2b). El ViewModel actúa de Host: reacciona a escenas / device
    // commands y re-arma el wake word al cerrar.
    private val engine = ConversationEngine(
        context = application.applicationContext,
        scope = viewModelScope,
        cloudRunUrl = BuildConfig.CLOUD_RUN_URL,
        apiKey = BuildConfig.KIWI_API_KEY,
        host = this,
    )
    val pipeline: StateFlow<PipelineState> get() = engine.pipeline

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

    // Updater para el botón "Actualizar" del Home. Mismo gate que el
    // polling periódico de MainActivity: no instala en mitad de una
    // sesión. _updateStatus es un mensaje transitorio que el chip
    // muestra unos segundos (null = botón en reposo "Actualizar").
    private val autoUpdater = AutoUpdater(application) {
        pipeline.value is PipelineState.Idle
    }
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    /**
     * El usuario pulsó "Actualizar" en el Home. Comprueba el backend,
     * y si hay versión nueva descarga + instala (silenciosa con Device
     * Owner; diálogo normal en dev). Refleja el progreso en
     * [updateStatus] para feedback, y lo limpia tras unos segundos.
     */
    fun onCheckForUpdate() {
        if (_updateStatus.value != null) return  // ya hay un chequeo en curso
        viewModelScope.launch {
            _updateStatus.value = "Buscando…"
            val result = runCatching { autoUpdater.checkForUpdate() }
                .getOrDefault(AutoUpdater.Result.NO_NETWORK)
            _updateStatus.value = when (result) {
                AutoUpdater.Result.UP_TO_DATE -> "Ya al día"
                AutoUpdater.Result.UPDATING -> "Actualizando…"
                AutoUpdater.Result.BUSY -> "Ocupado, reintenta"
                AutoUpdater.Result.NO_NETWORK -> "Sin conexión"
                AutoUpdater.Result.NO_APK -> "Sin APK"
            }
            // "Actualizando…" lo dejamos hasta que el SO reinicie la
            // app con la versión nueva; el resto se autolimpian.
            if (result != AutoUpdater.Result.UPDATING) {
                delay(4000)
                _updateStatus.value = null
            }
        }
    }

    private val homePoller = HomeStatePoller(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
    )

    private val todoApi = TodoApi(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
    )

    // Cliente HTTP + repositorio reactivo del estado de Spotify.
    // ``spotifyApi`` ofrece los POST/GET puntuales (play, pause,
    // search, library…); ``spotifyState`` expone un StateFlow alimentado
    // por SSE para que cualquier scene que pinte el player vea cambios
    // en <1s sin polling.
    private val spotifyApi = SpotifyApi(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
    )

    private val spotifyState = SpotifyStateRepository(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        devToken = BuildConfig.DEV_LOGS_TOKEN,
        api = spotifyApi,
    ).also { it.start(viewModelScope) }

    /**
     * Reactive view of Spotify player state. ``null`` antes del
     * primer fetch (red caída en arranque); luego se actualiza vía
     * SSE.
     */
    val spotifyPlayer: StateFlow<SpotifyState?>
        get() = spotifyState.state

    /**
     * Lista de dispositivos para el SpotifyDeviceSheet. Se llena bajo
     * demanda — abrir el sheet la refresca; entre tanto vive en
     * memoria pero stale es preferible a vacío.
     */
    private val _spotifyDevices = MutableStateFlow<List<SpotifyDevice>>(emptyList())
    val spotifyDevices: StateFlow<List<SpotifyDevice>> =
        _spotifyDevices.asStateFlow()

    /** Cola actual; se llena cuando se abre el QueueSheet. */
    private val _spotifyQueue =
        MutableStateFlow<SpotifyQueue?>(null)
    val spotifyQueue: StateFlow<SpotifyQueue?> =
        _spotifyQueue.asStateFlow()

    /** Bottom-sheet de devices visible si != null. */
    private val _spotifyDeviceSheetOpen = MutableStateFlow(false)
    val spotifyDeviceSheetOpen: StateFlow<Boolean> = _spotifyDeviceSheetOpen.asStateFlow()

    private val _spotifyQueueSheetOpen = MutableStateFlow(false)
    val spotifyQueueSheetOpen: StateFlow<Boolean> = _spotifyQueueSheetOpen.asStateFlow()

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
                        pipeline.value is PipelineState.Idle
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
            if (_scene.value is Scene.Idle && pipeline.value is PipelineState.Idle) {
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
        val snapshot = _homeSnapshot.value
        enterScene(
            Scene.Calendar(
                period = "today",
                events = snapshot?.eventsToday.orEmpty(),
                error = snapshot?.eventsTodayError,
            ),
        )
    }

    /**
     * El usuario pulsó "Renovar Google" (típicamente en el empty
     * state de Calendar cuando el OAuth caducó). Abre el flujo
     * web del backend en el navegador del sistema; al volver de
     * Google, el callback persiste el nuevo refresh token y el
     * próximo /api/home ya trae la agenda otra vez.
     *
     * Si no hay navegador instalado se loggea pero no hacemos
     * más — el flujo manual sigue funcionando vía móvil/PC.
     */
    fun onRenovarGoogleClick() {
        val baseUrl = BuildConfig.CLOUD_RUN_URL.trimEnd('/')
        val token = BuildConfig.DEV_LOGS_TOKEN
        if (baseUrl.isEmpty() || token.isEmpty()) {
            KLog.w(TAG, "onRenovarGoogleClick: missing CLOUD_RUN_URL or DEV_LOGS_TOKEN")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/oauth/google/start?token=$token")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (exc: Exception) {
            // Tablet sin navegador (poco probable) — el flujo
            // manual sigue funcionando desde móvil/PC.
            KLog.w(TAG, "onRenovarGoogleClick: no browser? $exc")
        }
    }

    /**
     * Tap en la barra "Suena ahora" del home → pantalla NowPlaying
     * grande. Primero pintamos lo que tenemos en el chip (carátula +
     * título + artista) para que la transición sea inmediata, luego
     * el ``spotifyState`` (alimentado por SSE) se encarga de
     * sustituirlo por el snapshot completo con duración, progreso,
     * shuffle, repeat, etc.
     */
    fun onOpenNowPlaying() {
        val live = spotifyState.state.value
        val live_track = live?.track
        val scene = if (live != null && live_track != null) {
            Scene.NowPlaying(
                title = live_track.title,
                artist = live_track.artist,
                album = live_track.album,
                albumArtUrl = live_track.albumArtUrl,
                isPlaying = live.playing,
                durationMs = live.durationMs,
                progressMs = live.progressMs,
                trackUri = live_track.uri,
                shuffle = live.shuffle,
                repeatState = live.repeatState,
                liked = live.liked,
                device = live.device,
            )
        } else {
            val chip = _homeSnapshot.value?.nowPlaying ?: return
            Scene.NowPlaying(
                title = chip.title,
                artist = chip.artist,
                album = "",
                albumArtUrl = chip.albumArtUrl,
                isPlaying = true,
                durationMs = 0L,
                progressMs = 0L,
            )
        }
        enterScene(scene)
    }

    /**
     * Abre el SpotifyHub (carruseles de biblioteca + descubrimiento).
     * Fetch en background del paquete completo; entramos a la scene
     * vacía para que la transición sea instantánea, luego
     * reemplazamos cuando arriba el contenido.
     */
    fun onOpenSpotifyHub() {
        enterScene(Scene.SpotifyHub(sections = emptyList()))
        viewModelScope.launch(Dispatchers.IO) {
            val sections = spotifyApi.fetchHub()
            if (_scene.value is Scene.SpotifyHub) {
                enterScene(Scene.SpotifyHub(sections = sections))
            }
        }
    }

    /** Tap en una row de un carrusel del Hub → reproducir + abrir NowPlaying. */
    fun onSpotifyHubItemTap(item: SpotifyResultItem, kind: String) {
        // Tracks individuales: play uri. Playlists / albums / artists:
        // play context. Artistas en particular tienen su "top tracks"
        // como contexto por defecto cuando se les pasa a /me/player/play.
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.play(uri = item.uri)
            // Tras pulsar play, abre NowPlaying optimista — el SSE
            // pintará el resto cuando llegue.
        }
        openNowPlayingOptimistic(item, kind)
    }

    /**
     * Tap en una row de [Scene.SpotifyResults] (search / library /
     * recommend). Mismo flow que el Hub: play + NowPlaying.
     */
    fun onSpotifyResultTap(item: SpotifyResultItem, kind: String) {
        onSpotifyHubItemTap(item, kind)
    }

    /** Long-press en una row de SpotifyResults → añadir a cola. */
    fun onSpotifyResultLongPress(item: SpotifyResultItem) {
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.addToQueue(uri = item.uri)
        }
    }

    /**
     * "Optimistic" play: entramos a NowPlaying con la info que
     * tenemos del item, mientras el SSE rellena lo que falta
     * (progresión, duración real, device, etc.).
     */
    private fun openNowPlayingOptimistic(
        item: SpotifyResultItem,
        kind: String,
    ) {
        enterScene(
            Scene.NowPlaying(
                title = item.title,
                artist = if (kind == "artist") "" else item.artist,
                album = item.album,
                albumArtUrl = item.albumArtUrl,
                isPlaying = true,
                durationMs = item.durationMs,
                progressMs = 0L,
                trackUri = item.uri,
            ),
        )
    }

    // ---- NowPlayingScene callbacks (tap-to-control) -------------

    fun onPlayPause() {
        val playing = spotifyState.state.value?.playing ?: false
        // Optimistic flip — el SSE lo confirmará en breve.
        spotifyState.applyOptimistic { s -> s?.copy(playing = !playing) }
        viewModelScope.launch(Dispatchers.IO) {
            if (playing) spotifyApi.pause() else spotifyApi.resume()
        }
    }

    fun onNext() {
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.next()
            // Refresh manual: las pistas cambian rápido y el SSE
            // tarda hasta 2s en el peor caso.
            kotlinx.coroutines.delay(250)
            spotifyState.refresh()
        }
    }

    fun onPrevious() {
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.previous()
            kotlinx.coroutines.delay(250)
            spotifyState.refresh()
        }
    }

    fun onSeek(positionMs: Long) {
        spotifyState.applyOptimistic { s -> s?.copy(progressMs = positionMs) }
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.seek(positionMs)
        }
    }

    fun onToggleShuffle() {
        val next = !(spotifyState.state.value?.shuffle ?: false)
        spotifyState.applyOptimistic { s -> s?.copy(shuffle = next) }
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.setShuffle(next)
        }
    }

    fun onCycleRepeat() {
        val current = spotifyState.state.value?.repeatState ?: "off"
        val next = when (current) {
            "off" -> "context"
            "context" -> "track"
            else -> "off"
        }
        spotifyState.applyOptimistic { s -> s?.copy(repeatState = next) }
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.setRepeat(next)
        }
    }

    fun onToggleLike() {
        val liked = spotifyState.state.value?.liked == true
        spotifyState.applyOptimistic { s -> s?.copy(liked = !liked) }
        viewModelScope.launch(Dispatchers.IO) {
            if (liked) spotifyApi.unlike() else spotifyApi.like()
        }
    }

    fun onOpenSpotifyDeviceSheet() {
        _spotifyDeviceSheetOpen.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _spotifyDevices.value = spotifyApi.fetchDevices()
        }
    }

    fun onCloseSpotifyDeviceSheet() {
        _spotifyDeviceSheetOpen.value = false
    }

    fun onSpotifyDevicePick(device: SpotifyDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            spotifyApi.transferToDevice(device.id)
            kotlinx.coroutines.delay(400)
            spotifyState.refresh()
            _spotifyDevices.value = spotifyApi.fetchDevices()
        }
    }

    /**
     * Volumen contextual: si el device activo es el tablet (o no hay
     * device remoto), usamos AudioManager local. Si es remoto, pasamos
     * por la API REST → Spotify Connect → device.
     */
    fun onSpotifyVolumeChange(percent: Int) {
        val device = spotifyState.state.value?.device
        if (device != null && !device.type.contains("tablet", ignoreCase = true)
            && device.supportsVolume
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                spotifyApi.setVolume(percent)
            }
        } else {
            // Local — usar AudioManager (mismo handler que set_volume).
            applyVolume(level = percent, delta = null)
        }
        // Optimistic local update para que el slider responda.
        spotifyState.applyOptimistic { s ->
            val d = s?.device ?: return@applyOptimistic s
            s.copy(device = d.copy(volumePercent = percent))
        }
    }

    fun onOpenSpotifyQueueSheet() {
        _spotifyQueueSheetOpen.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _spotifyQueue.value = spotifyApi.fetchQueue()
        }
    }

    fun onCloseSpotifyQueueSheet() {
        _spotifyQueueSheetOpen.value = false
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

    // Wake-word listener (Vosk ASR offline en español). Posee el micro
    // mientras la app está Idle y lo libera al disparar el wake word o
    // cuando el usuario toca para arrancar manualmente. El pipeline de
    // conversación (captura, VAD, sesión, playback) vive ahora en
    // [engine]; aquí solo queda el wake word.
    private val wakeWordListener = VoskKeywordListener(application)

    private var permissionGranted = false

    fun setMicrophonePermission(granted: Boolean) {
        val wasGranted = permissionGranted
        permissionGranted = granted
        // If permission has just been granted while we were sitting on
        // the clock, light up the wake-word listener so the user can
        // start talking right away. If it just got revoked, drop the
        // listener (it'd fail to read anyway).
        if (granted && !wasGranted && pipeline.value is PipelineState.Idle) {
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
        if (permissionGranted && pipeline.value is PipelineState.Idle) {
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
            // Release the mic before the engine spins up its own
            // capture. start()/stop() on the listener is synchronous
            // enough que esto sea seguro back-to-back en el hilo Main.
            wakeWordListener.stop()
            openConversation()
        }
        if (!started) {
            KLog.w(TAG, "wake-word listener failed to start")
        }
    }

    /**
     * Arranca una conversación: libera el micro del wake word y abre
     * el motor. El motor valida permiso/config y monta el WebSocket.
     */
    private fun openConversation() {
        wakeWordListener.stop()
        engine.open(permissionGranted)
    }

    fun onTap() {
        when (pipeline.value) {
            PipelineState.Idle -> openConversation()
            is PipelineState.Error -> {
                // Recuperar de un error: cerrar (Idle), volver al reloj
                // y re-armar el wake word para poder volver a llamar.
                closeConversation(resetScene = true)
            }
            // Resto de estados activos (Connecting/Reconnecting/
            // Listening/Processing/Responding) los gestiona el motor:
            // en Listening termina el turno, en el resto ignora.
            else -> engine.onActiveTap()
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
            if (pipeline.value is PipelineState.Idle) {
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
        if (pipeline.value !is PipelineState.Idle) closeConversation(resetScene = false)
    }

    /**
     * Full reset back to the home (clock) scene. Wired to long-press
     * because the gesture is a strong "go home" affordance — it should
     * wipe both the conversation and any scene the tablet is showing.
     */
    fun onLongPress() {
        if (pipeline.value !is PipelineState.Idle || _scene.value !is Scene.Idle) {
            closeConversation(resetScene = true)
        }
    }

    // ---- ConversationEngine.Host ------------------------------------

    /**
     * Una tool empujó una escena. enterScene maneja smart-push (mismo
     * tipo = replace sin meter al stack). Si es una AlarmList, además
     * reconciliamos el scheduler del SO al momento para que "ponme un
     * despertador en 2 min" no espere al próximo /api/home.
     */
    override fun onSceneSet(scene: Scene) {
        enterScene(scene)
        if (scene is Scene.AlarmList) {
            runCatching { alarmScheduler.sync(scene.items) }
                .onFailure { e ->
                    KLog.w(
                        TAG,
                        "alarmScheduler.sync from scene failed: " +
                            "${e::class.simpleName}: ${e.message}",
                    )
                }
        }
    }

    override fun onDeviceCommand(event: KiwiSessionEvent.DeviceCommand) {
        handleDeviceCommand(event)
    }

    /** Tras una respuesta: cerrar si estamos en una escena pasiva
     *  (reproducción de vídeo/música), si no abrir el siguiente turno. */
    override fun shouldCloseAfterResponse(): Boolean =
        isPassiveConsumptionScene(_scene.value)

    /** El motor cerró por su cuenta (no-speech / cierre del server /
     *  cierre tras respuesta pasiva): re-armamos wake word + refresco. */
    override fun onClosed() {
        afterConversationClosed(resetScene = false)
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
            "open_app_url" -> {
                val url = event.url ?: return
                openUrlInApp(url, event.packageName)
            }
            "set_volume" -> applyVolume(level = event.level, delta = event.delta)
            "media_key" -> dispatchMediaKey(event.label)
            "ui_click" -> {
                val label = event.label
                val svc = KiwiAccessibilityService.instance
                when {
                    label.isNullOrBlank() -> KLog.w(TAG, "ui_click sin etiqueta")
                    svc == null -> KLog.w(TAG, "ui_click: AccessibilityService no habilitado")
                    else -> KLog.i(TAG, "ui_click('$label') → ${svc.clickByLabel(label)}")
                }
            }
            else -> KLog.w(TAG, "unknown device_command: ${event.command}")
        }
    }

    /**
     * Manda un media key (play / pause / next / previous) al sistema.
     * Cubre el escenario "no estoy seguro qué app de medios está
     * sonando" — el SO enruta la pulsación a la sesión activa
     * (típicamente la última en hacer foco de audio).
     *
     * Implementado vía broadcast intent que MediaSessionManager
     * captura. No requiere notification listener service; funciona
     * out-of-the-box en Android 5+.
     */
    private fun dispatchMediaKey(label: String?) {
        // Prefer el MediaSessionMonitor si está activo: enruta a la
        // sesión exacta en vez del broadcast a ciegas (más fiable y
        // funciona aunque el SO esté confundido sobre quién tiene
        // foco de audio).
        val monitor = com.kiwi.assistant.service.MediaSessionMonitor.instance
        val action = when (label?.lowercase()) {
            "pause" -> com.kiwi.assistant.service.MediaSessionMonitor.TransportAction.PAUSE
            "play" -> com.kiwi.assistant.service.MediaSessionMonitor.TransportAction.PLAY
            "play_pause", "toggle" -> null   // sin equivalente directo; cae a media key
            "next" -> com.kiwi.assistant.service.MediaSessionMonitor.TransportAction.NEXT
            "previous", "prev" -> com.kiwi.assistant.service.MediaSessionMonitor.TransportAction.PREVIOUS
            else -> {
                KLog.w(TAG, "media_key: etiqueta desconocida $label")
                return
            }
        }
        if (monitor != null && action != null && monitor.dispatchTransportAction(action)) {
            KLog.i(TAG, "media_key('$label') via MediaSessionMonitor")
            return
        }
        // Fallback: broadcast media key al SO.
        val keycode = when (label?.lowercase()) {
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "play_pause", "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val ctx = getApplication<Application>().applicationContext
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            KLog.w(TAG, "media_key: AudioManager unavailable")
            return
        }
        audio.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keycode),
        )
        audio.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keycode),
        )
        KLog.i(TAG, "media_key('$label') dispatched via broadcast")
    }

    /**
     * Abre una URL en una app concreta (deep link). Usado por los
     * tools de YouTube para reproducir / abrir Watch Later / etc. en
     * la app nativa, donde aplica tu cuenta + Premium y no hay las
     * restricciones de embed (error 152-4) del WebView.
     *
     * Si la app pedida no está instalada, cae a abrir la URL sin
     * package (el SO elige: navegador u otra app que la maneje), así
     * el deep link sigue funcionando en un tablet sin la app de
     * YouTube. Cierra la conversación: el usuario se va a ver el
     * vídeo, no tiene sentido dejar el micro abierto.
     */
    private fun openUrlInApp(url: String, packageName: String?) {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
        }
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!launched && packageName != null) {
            // App no instalada / no resuelve → reintento sin package.
            KLog.w(TAG, "open_app_url: $packageName no resolvió, abriendo sin package")
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(fallback) }.onFailure {
                KLog.w(TAG, "open_app_url fallback falló: ${it.message}")
            }
        }
        // El usuario se va a la app a consumir contenido — cerrar la
        // conversación para no dejar el micro escuchando de fondo.
        // resetScene=false: al volver de YouTube se queda la última
        // escena (p.ej. la lista de búsqueda) en vez del home pelado,
        // así puede pedir "oye kiwi, pon otro" sobre la misma lista.
        closeConversation(resetScene = false)
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

    /**
     * Cierre explícito (long-press, X, o tras un device_command que
     * lanza otra app). Tira abajo el pipeline vía [engine] y hace lo
     * de host: reset de escena (si toca), re-armar wake word, refresco.
     */
    private fun closeConversation(resetScene: Boolean) {
        engine.close()
        afterConversationClosed(resetScene)
    }

    /**
     * Trabajo de host tras cerrar una conversación (lo llama tanto
     * [closeConversation] como [onClosed] cuando el motor cierra solo).
     */
    private fun afterConversationClosed(resetScene: Boolean) {
        if (resetScene) resetToHome()
        // Re-arm the wake-word listener regardless: con el pipeline
        // cerrado, el usuario puede volver a llamar a Kiwi con la
        // escena (calendar / lo que sea) aún en pantalla.
        startWakeWordListener()
        if (_scene.value is Scene.Idle) triggerHomeRefresh()
    }

    override fun onCleared() {
        engine.shutdown()
        wakeWordListener.stop()
        homePollerJob.cancel()
        sceneAutoCloseJob.cancel()
        eventSoonJob.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "KiwiViewModel"

        // How often we re-pull /api/home in the background. Pensado
        // para que el chip "Suena ahora" desaparezca rápido cuando
        // pausas Spotify desde otro device (el backend ya filtra
        // is_playing=true, pero el tablet solo lo nota en el siguiente
        // poll). Un minuto da sensación de "vivo" sin saturar quotas:
        // Calendar y Spotify aceptan mucho más, y el weather se cachea
        // 10 min internamente en el backend.
        const val HOME_REFRESH_INTERVAL_MS = 60_000L

        // (SILENCE_END_OF_TURN_MS / NO_SPEECH_TIMEOUT_MS /
        // MAX_CONNECT_ATTEMPTS viven ahora en ConversationEngine, que
        // es quien gestiona los turnos y la reconexión.)

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
