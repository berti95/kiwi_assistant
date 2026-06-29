package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.SpotifyDevice
import com.kiwi.assistant.ui.SpotifyHubSection
import com.kiwi.assistant.ui.SpotifyResultItem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST client for the ``/api/spotify/...`` endpoints.
 *
 * Espejo de [com.kiwi.assistant.network.TodoApi] pero para Spotify:
 *  - Cada función es ``suspend`` y devuelve un DTO Kotlin o ``null``
 *    si la petición falló (network, 4xx/5xx, JSON malformado).
 *  - Todas las llamadas usan el mismo ``dev_token`` que el resto de
 *    endpoints de tablet (gated por backend).
 *  - Errores se loggean a [KLog] pero no se propagan — la UI degrada
 *    en silencio (mantener el estado anterior es mejor que un crash).
 *
 * Pensado para llamarse desde corutinas del ViewModel; la I/O es
 * blocking → siempre llamarlo en ``Dispatchers.IO``.
 */
class SpotifyApi(
    private val baseUrl: String,
    private val devToken: String,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val available: Boolean
        get() = baseUrl.isNotEmpty() && devToken.isNotEmpty()

    private fun urlOf(path: String, vararg params: Pair<String, String?>): String {
        val base = "${baseUrl.trimEnd('/')}$path"
        val all = listOf("token" to devToken) + params.mapNotNull { (k, v) ->
            v?.let { k to it }
        }
        val query = all.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        return "$base?$query"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private suspend fun executeGet(path: String, vararg params: Pair<String, String?>): JsonObject? =
        withContext(Dispatchers.IO) {
            if (!available) return@withContext null
            val request = Request.Builder().url(urlOf(path, *params)).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    parseJsonBody(path, response)
                }
            } catch (e: Exception) {
                KLog.w(TAG, "GET $path failed: ${e::class.simpleName}: ${e.message}")
                null
            }
        }

    private suspend fun executePost(
        path: String, vararg params: Pair<String, String?>,
    ): JsonObject? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val request = Request.Builder()
            .url(urlOf(path, *params))
            .post(EMPTY_BODY)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                parseJsonBody(path, response)
            }
        } catch (e: Exception) {
            KLog.w(TAG, "POST $path failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseJsonBody(path: String, response: Response): JsonObject? {
        if (!response.isSuccessful) {
            // 4xx/5xx → log y devolvemos null. La UI mantiene su
            // último estado conocido en vez de mostrar basura.
            KLog.w(TAG, "$path returned ${response.code}")
            return null
        }
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(body) as? JsonObject
        }.getOrNull()
    }

    // ---- state ---------------------------------------------------

    /** Snapshot completo del player; ``null`` si la red falla. */
    suspend fun fetchState(): SpotifyState? {
        val root = executeGet("/api/spotify/state") ?: return null
        return parseState(root)
    }

    suspend fun fetchDevices(): List<SpotifyDevice> {
        val root = executeGet("/api/spotify/devices") ?: return emptyList()
        val arr = root["devices"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseDevice) }
    }

    // ---- playback control ---------------------------------------

    suspend fun play(uri: String? = null, query: String? = null): Boolean =
        executePost(
            "/api/spotify/play",
            "uri" to uri,
            "query" to query,
        ) != null

    suspend fun pause(): Boolean = executePost("/api/spotify/pause") != null
    suspend fun resume(): Boolean = executePost("/api/spotify/resume") != null
    suspend fun next(): Boolean = executePost("/api/spotify/next") != null
    suspend fun previous(): Boolean = executePost("/api/spotify/previous") != null

    suspend fun seek(positionMs: Long): Boolean =
        executePost("/api/spotify/seek", "position_ms" to positionMs.toString()) != null

    suspend fun setShuffle(enabled: Boolean): Boolean =
        executePost("/api/spotify/shuffle", "enabled" to enabled.toString()) != null

    suspend fun setRepeat(mode: String): Boolean =
        executePost("/api/spotify/repeat", "mode" to mode) != null

    /** Volumen del device activo en %. Spotify clampea a 0-100. */
    suspend fun setVolume(percent: Int): Boolean =
        executePost("/api/spotify/volume", "percent" to percent.toString()) != null

    suspend fun transferToDevice(deviceId: String): Boolean =
        executePost("/api/spotify/transfer", "device_id" to deviceId) != null

    suspend fun transferToName(target: String): Boolean =
        executePost("/api/spotify/transfer", "target" to target) != null

    // ---- library -----------------------------------------------

    suspend fun like(uri: String? = null): Boolean =
        executePost("/api/spotify/like", "uri" to uri) != null

    suspend fun unlike(uri: String? = null): Boolean =
        executePost("/api/spotify/unlike", "uri" to uri) != null

    suspend fun addToQueue(uri: String? = null, query: String? = null): Boolean =
        executePost(
            "/api/spotify/queue",
            "uri" to uri,
            "query" to query,
        ) != null

    suspend fun fetchQueue(): SpotifyQueue? {
        val root = executeGet("/api/spotify/queue") ?: return null
        val current = (root["currently_playing"] as? JsonObject)?.let(::parseResultItem)
        val arr = root["queue"] as? JsonArray ?: return SpotifyQueue(current, emptyList())
        val items = arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseResultItem) }
        return SpotifyQueue(current, items)
    }

    suspend fun fetchMyPlaylists(limit: Int = 20): List<SpotifyResultItem> =
        fetchItemsList("/api/spotify/library/playlists", "limit" to limit.toString())

    suspend fun fetchRecentlyPlayed(limit: Int = 20): List<SpotifyResultItem> =
        fetchItemsList("/api/spotify/library/recently_played", "limit" to limit.toString())

    suspend fun fetchLikedSongs(limit: Int = 30, offset: Int = 0): List<SpotifyResultItem> =
        fetchItemsList(
            "/api/spotify/library/liked",
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        )

    suspend fun fetchFeaturedPlaylists(limit: Int = 12): List<SpotifyResultItem> =
        fetchItemsList("/api/spotify/library/featured", "limit" to limit.toString())

    suspend fun fetchTopTracks(limit: Int = 20): List<SpotifyResultItem> =
        fetchItemsList("/api/spotify/library/top_tracks", "limit" to limit.toString())

    suspend fun fetchTopArtists(limit: Int = 20): List<SpotifyResultItem> =
        fetchItemsList("/api/spotify/library/top_artists", "limit" to limit.toString())

    suspend fun fetchPlaylistTracks(uri: String, limit: Int = 50): List<SpotifyResultItem> {
        val root = executeGet(
            "/api/spotify/playlist",
            "uri" to uri,
            "limit" to limit.toString(),
        ) ?: return emptyList()
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseResultItem) }
    }

    suspend fun fetchHub(): List<SpotifyHubSection> {
        // 5 secciones en paralelo. Cada una tolera fallos: si una falla,
        // se omite y el hub se renderiza con las que sí cargaron.
        return withContext(Dispatchers.IO) {
            val recent = kotlinx.coroutines.async { fetchRecentlyPlayed(12) }
            val mine = kotlinx.coroutines.async { fetchMyPlaylists(20) }
            val featured = kotlinx.coroutines.async { fetchFeaturedPlaylists(12) }
            val topTracks = kotlinx.coroutines.async { fetchTopTracks(20) }
            val topArtists = kotlinx.coroutines.async { fetchTopArtists(20) }
            val sections = mutableListOf<SpotifyHubSection>()
            recent.await().takeIf { it.isNotEmpty() }?.let {
                sections += SpotifyHubSection(
                    id = "recent",
                    title = "Escuchado recientemente",
                    kind = "track",
                    items = it,
                )
            }
            mine.await().takeIf { it.isNotEmpty() }?.let {
                sections += SpotifyHubSection(
                    id = "mine",
                    title = "Tus playlists",
                    kind = "playlist",
                    items = it,
                )
            }
            featured.await().takeIf { it.isNotEmpty() }?.let {
                sections += SpotifyHubSection(
                    id = "featured",
                    title = "Hechas para ti",
                    kind = "playlist",
                    items = it,
                )
            }
            topTracks.await().takeIf { it.isNotEmpty() }?.let {
                sections += SpotifyHubSection(
                    id = "top_tracks",
                    title = "Tus canciones más escuchadas",
                    kind = "track",
                    items = it,
                )
            }
            topArtists.await().takeIf { it.isNotEmpty() }?.let {
                sections += SpotifyHubSection(
                    id = "top_artists",
                    title = "Tus artistas",
                    kind = "artist",
                    items = it,
                )
            }
            sections
        }
    }

    private suspend fun fetchItemsList(
        path: String, vararg params: Pair<String, String?>,
    ): List<SpotifyResultItem> {
        val root = executeGet(path, *params) ?: return emptyList()
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseResultItem) }
    }

    // ---- search / recommend -----------------------------------

    suspend fun search(
        query: String, kind: String = "track", limit: Int = 20,
    ): List<SpotifyResultItem> {
        val root = executeGet(
            "/api/spotify/search",
            "q" to query, "kind" to kind, "limit" to limit.toString(),
        ) ?: return emptyList()
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseResultItem) }
    }

    suspend fun recommend(
        seedTrack: String? = null,
        seedArtist: String? = null,
        seedGenre: String? = null,
        mood: String? = null,
        limit: Int = 20,
    ): List<SpotifyResultItem> {
        val root = executeGet(
            "/api/spotify/recommend",
            "seed_track" to seedTrack,
            "seed_artist" to seedArtist,
            "seed_genre" to seedGenre,
            "mood" to mood,
            "limit" to limit.toString(),
        ) ?: return emptyList()
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let(::parseResultItem) }
    }

    // ---- parsers ----------------------------------------------

    private fun parseState(obj: JsonObject): SpotifyState {
        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
        fun bool(k: String, default: Boolean = false): Boolean =
            (obj[k] as? JsonPrimitive)?.booleanOrNull ?: default
        fun long(k: String, default: Long = 0L): Long =
            (obj[k] as? JsonPrimitive)?.longOrNull ?: default

        val track = (obj["track"] as? JsonObject)?.let(::parseResultItem)
        val device = (obj["device"] as? JsonObject)?.let(::parseDevice)
        val liked = (obj["liked"] as? JsonPrimitive)?.booleanOrNull

        return SpotifyState(
            available = bool("available", true),
            playing = bool("playing"),
            durationMs = long("duration_ms"),
            progressMs = long("progress_ms"),
            shuffle = bool("shuffle"),
            repeatState = str("repeat_state") ?: "off",
            liked = liked,
            track = track,
            device = device,
        )
    }

    private fun parseResultItem(obj: JsonObject): SpotifyResultItem? {
        val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull ?: return null
        return SpotifyResultItem(
            uri = uri,
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            artist = (obj["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            album = (obj["album"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            albumArtUrl = (obj["album_art_url"] as? JsonPrimitive)?.contentOrNull,
            owner = (obj["owner"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            itemCount = (obj["item_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            durationMs = (obj["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    private fun parseDevice(obj: JsonObject): SpotifyDevice? {
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        return SpotifyDevice(
            id = id,
            name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            isActive = (obj["is_active"] as? JsonPrimitive)?.booleanOrNull ?: false,
            volumePercent = (obj["volume_percent"] as? JsonPrimitive)?.intOrNull,
            supportsVolume = (obj["supports_volume"] as? JsonPrimitive)?.booleanOrNull ?: false,
            isRestricted = (obj["is_restricted"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private companion object {
        const val TAG = "SpotifyApi"
        val EMPTY_BODY = "".toRequestBody("application/json".toMediaType())
    }
}

/**
 * Snapshot completo del player. Espejo de
 * ``GET /api/spotify/state``. ``available=false`` con ``track=null``
 * significa "Spotify no configurado o nada sonando".
 */
data class SpotifyState(
    val available: Boolean,
    val playing: Boolean,
    val durationMs: Long,
    val progressMs: Long,
    val shuffle: Boolean,
    val repeatState: String,
    val liked: Boolean?,
    val track: SpotifyResultItem?,
    val device: SpotifyDevice?,
)

/**
 * Snapshot de la cola actual. ``current`` es la pista en curso;
 * ``upcoming`` son las siguientes en orden de reproducción.
 */
data class SpotifyQueue(
    val current: SpotifyResultItem?,
    val upcoming: List<SpotifyResultItem>,
)
