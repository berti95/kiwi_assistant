package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.SpotifyDevice
import com.kiwi.assistant.ui.SpotifyResultItem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Repositorio reactivo del estado de Spotify.
 *
 * Combina dos fuentes:
 *
 * 1. **SSE** (``/api/spotify/stream``) — la fuente preferida. Cada
 *    evento del backend reemplaza el [state] cacheado y el tick local
 *    interpola ``progressMs`` entre eventos.
 * 2. **Polling REST** (``/api/spotify/state``) — fallback cuando SSE
 *    no conecta o se cae. Activo solo mientras alguien observa
 *    [state] y el SSE no consigue mantenerse vivo.
 *
 * Diseño:
 *  - ``start(scope)`` lanza una corutina que mantiene el SSE conectado
 *    con reconexión exponencial. Llamar ``stop()`` para liberar.
 *  - El tick de progreso es un ``Job`` separado que solo corre cuando
 *    el state actual está playing; se cancela al pausar.
 *  - Errores se loggean pero NO se propagan en el flow — el caller
 *    siempre lee el último snapshot conocido.
 */
class SpotifyStateRepository(
    private val baseUrl: String,
    private val devToken: String,
    private val api: SpotifyApi,
) {
    private val _state = MutableStateFlow<SpotifyState?>(null)
    val state: StateFlow<SpotifyState?> = _state.asStateFlow()

    private val available: Boolean
        get() = baseUrl.isNotEmpty() && devToken.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // unbounded for SSE
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var hostScope: CoroutineScope? = null
    private var connectorJob: Job? = null
    private var pollerJob: Job? = null
    private var progressTickJob: Job? = null
    @Volatile private var eventSource: EventSource? = null

    /** Comienza a mantener el state vivo. Idempotente. */
    fun start(scope: CoroutineScope) {
        if (!available) return
        if (connectorJob?.isActive == true) return
        hostScope = scope
        connectorJob = scope.launch { connectLoop(scope) }
        // Fetch inicial — el SSE también enviará un snapshot pero esto
        // pinta la UI inmediatamente sin esperar al primer evento.
        scope.launch { refresh() }
    }

    fun stop() {
        connectorJob?.cancel()
        pollerJob?.cancel()
        progressTickJob?.cancel()
        eventSource?.cancel()
        connectorJob = null
        pollerJob = null
        progressTickJob = null
        eventSource = null
        hostScope = null
    }

    /** Fuerza un refetch via REST (útil tras un POST manual). */
    suspend fun refresh() {
        val fresh = api.fetchState() ?: return
        applyState(fresh)
    }

    /**
     * Reemplaza el state actual sin tocar el SSE — usado por el
     * ViewModel para optimistic UI: el usuario pulsa play → cambiamos
     * ``playing=true`` localmente sin esperar a que SSE confirme.
     */
    fun applyOptimistic(transform: (SpotifyState?) -> SpotifyState?) {
        val current = _state.value
        val next = transform(current) ?: return
        applyState(next)
    }

    private fun applyState(state: SpotifyState) {
        _state.value = state
        retunneProgressTick()
    }

    private fun retunneProgressTick() {
        progressTickJob?.cancel()
        val state = _state.value ?: return
        if (!state.playing || state.durationMs <= 0) return
        // Tick local 4 Hz para que la barra de progreso se mueva
        // entre eventos SSE.
        val scope = hostScope ?: return
        progressTickJob = scope.launch {
            while (true) {
                delay(250)
                val current = _state.value ?: return@launch
                if (!current.playing) return@launch
                val nextMs = (current.progressMs + 250)
                    .coerceAtMost(current.durationMs)
                _state.value = current.copy(progressMs = nextMs)
                if (nextMs >= current.durationMs) {
                    // Próxima pista llegará via SSE en breve; no
                    // adivinamos nada, simplemente paramos el tick.
                    return@launch
                }
            }
        }
    }

    private suspend fun connectLoop(scope: CoroutineScope) {
        var backoffMs = 1_000L
        while (true) {
            val ok = connectSse(scope)
            if (ok) {
                backoffMs = 1_000L  // reset al primer evento exitoso
            }
            // Si el SSE se cae, levantamos el poller de fallback
            // durante el backoff.
            ensurePoller(scope)
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun connectSse(scope: CoroutineScope): Boolean {
        if (!available) return false
        val url = "${baseUrl.trimEnd('/')}/api/spotify/stream?token=$devToken"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val factory = EventSources.createFactory(client)
            val es = factory.newEventSource(request, object : EventSourceListener() {
                @Volatile private var sawEvent = false

                override fun onOpen(eventSource: EventSource, response: Response) {
                    KLog.i(TAG, "SSE open")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    sawEvent = true
                    cancelPoller()  // SSE healthy → no más polling
                    try {
                        val obj = json.parseToJsonElement(data) as? JsonObject ?: return
                        val stateObj = obj["state"] as? JsonObject ?: return
                        val state = parseStateLocally(stateObj) ?: return
                        applyState(state)
                    } catch (e: Exception) {
                        KLog.w(TAG, "SSE parse failed: ${e::class.simpleName}: ${e.message}")
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    KLog.i(TAG, "SSE closed")
                    this@SpotifyStateRepository.eventSource = null
                    if (cont.isActive) cont.resumeWith(Result.success(sawEvent))
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    KLog.w(
                        TAG,
                        "SSE failure: ${t?.message ?: "no exception"} " +
                            "code=${response?.code ?: "?"}",
                    )
                    this@SpotifyStateRepository.eventSource = null
                    if (cont.isActive) cont.resumeWith(Result.success(sawEvent))
                }
            })
            this.eventSource = es
            cont.invokeOnCancellation { es.cancel() }
        }
    }

    private fun cancelPoller() {
        pollerJob?.cancel()
        pollerJob = null
    }

    private fun ensurePoller(scope: CoroutineScope) {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun parseStateLocally(obj: JsonObject): SpotifyState? {
        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
        fun bool(k: String, default: Boolean = false): Boolean =
            (obj[k] as? JsonPrimitive)?.booleanOrNull ?: default
        fun long(k: String, default: Long = 0L): Long =
            (obj[k] as? JsonPrimitive)?.longOrNull ?: default
        val trackObj = obj["track"] as? JsonObject
        val deviceObj = obj["device"] as? JsonObject
        val track = trackObj?.let {
            val uri = (it["uri"] as? JsonPrimitive)?.contentOrNull ?: return@let null
            SpotifyResultItem(
                uri = uri,
                title = (it["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                artist = (it["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                album = (it["album"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                albumArtUrl = (it["album_art_url"] as? JsonPrimitive)?.contentOrNull,
                durationMs = (it["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
        val device = deviceObj?.let {
            val id = (it["id"] as? JsonPrimitive)?.contentOrNull ?: return@let null
            SpotifyDevice(
                id = id,
                name = (it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                type = (it["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                isActive = (it["is_active"] as? JsonPrimitive)?.booleanOrNull ?: false,
                volumePercent = (it["volume_percent"] as? JsonPrimitive)?.intOrNull,
                supportsVolume = (it["supports_volume"] as? JsonPrimitive)?.booleanOrNull
                    ?: false,
            )
        }
        return SpotifyState(
            available = bool("available", true),
            playing = bool("playing"),
            durationMs = long("duration_ms"),
            progressMs = long("progress_ms"),
            shuffle = bool("shuffle"),
            repeatState = str("repeat_state") ?: "off",
            liked = (obj["liked"] as? JsonPrimitive)?.booleanOrNull,
            track = track,
            device = device,
        )
    }

    private companion object {
        const val TAG = "SpotifyStateRepository"
        const val POLL_INTERVAL_MS = 5_000L
    }
}
