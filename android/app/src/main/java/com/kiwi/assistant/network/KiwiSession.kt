package com.kiwi.assistant.network

import android.util.Base64
import com.kiwi.assistant.log.KLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.PlaylistItem
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.VideoItem

/**
 * High-level event surfaced to the ViewModel.
 *
 * SessionReady — server accepted the handshake; we can start streaming.
 * AudioOutput  — chunk of audio (PCM 24kHz, 16-bit mono) to play.
 * InputTranscript / OutputTranscript — text shown on screen.
 * ResponseEnd  — the model finished its turn, return to Idle.
 * Closed       — socket closed (clean or with code/reason).
 * Error        — something blew up (network, protocol, server-side).
 */
sealed interface KiwiSessionEvent {
    data object SessionReady : KiwiSessionEvent
    data class AudioOutput(val pcm: ByteArray) : KiwiSessionEvent
    data class InputTranscript(val text: String) : KiwiSessionEvent
    data class OutputTranscript(val text: String) : KiwiSessionEvent
    data object ResponseEnd : KiwiSessionEvent
    /**
     * Backend asked the tablet to switch to a new scene as a side
     * effect of a tool call (calendar listing, now-playing, …).
     * Carries the parsed [Scene] ready for the ViewModel to set.
     */
    data class SceneSet(val scene: Scene) : KiwiSessionEvent
    data class Closed(val code: Int, val reason: String) : KiwiSessionEvent
    data class Error(val message: String) : KiwiSessionEvent
}

/**
 * WebSocket client for /ws/session.
 *
 * Lifecycle: build → connect(onEvent) → sendAudio(...)/sendAudioEnd() →
 * close(). All callbacks land on OkHttp's dispatcher thread; the caller
 * is responsible for marshalling onto the main / IO thread as needed.
 *
 * The session converts the raw JSON traffic into KiwiSessionEvent objects
 * so the rest of the app stays out of the protocol details.
 */
class KiwiSession(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // unbounded for streaming
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        // kotlinx.serialization omits properties whose value equals their
        // declared default. The wire-protocol DTOs encode the message
        // discriminator as `val type: String = Protocol.TYPE_*`, so without
        // this flag every outbound frame would ship without its `type`
        // field and the backend would close the socket with code 4001
        // (CLOSE_EXPECTED_SESSION_START) on the very first message.
        encodeDefaults = true
    }
    private var webSocket: WebSocket? = null

    // Set when we initiate the close so a subsequent onFailure (typically
    // EOFException because the server doesn't always send a clean close
    // frame after we send session.end) doesn't bubble up as an error to
    // the UI on top of an otherwise successful interaction.
    @Volatile private var closedByClient = false

    fun connect(onEvent: (KiwiSessionEvent) -> Unit) {
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            onEvent(KiwiSessionEvent.Error("CLOUD_RUN_URL or KIWI_API_KEY missing"))
            return
        }

        val wsUrl = baseUrl.replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws/session"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(json.encodeToString(SessionStart.serializer(),
                    SessionStart(api_key = apiKey)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, onEvent)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(KiwiSessionEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByClient) {
                    // We initiated the close; OkHttp doesn't always see a
                    // clean close frame back so this surfaces as
                    // EOFException. Treat it as the normal close it is.
                    KLog.i(TAG, "WebSocket failure after client close: ${t::class.simpleName}")
                    onEvent(KiwiSessionEvent.Closed(1000, "closed by client"))
                    return
                }
                KLog.w(TAG, "WebSocket failure", t)
                val cls = t::class.simpleName ?: "Throwable"
                val msg = t.message?.takeIf { it.isNotBlank() }
                val http = response?.let { " HTTP ${it.code}" } ?: ""
                val composed = if (msg != null) "$cls: $msg$http" else "$cls$http"
                onEvent(KiwiSessionEvent.Error(composed))
            }
        })
    }

    fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        val data = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws.send(json.encodeToString(AudioInput.serializer(), AudioInput(data = data)))
    }

    fun sendActivityStart() {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(ActivityStart.serializer(), ActivityStart()))
    }

    fun sendActivityEnd() {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(ActivityEnd.serializer(), ActivityEnd()))
    }

    fun sendTurnCancel() {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(TurnCancel.serializer(), TurnCancel()))
    }

    fun sendAudioEnd() {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(AudioEnd.serializer(), AudioEnd()))
    }

    fun close() {
        val ws = webSocket ?: return
        closedByClient = true
        ws.send(json.encodeToString(SessionEnd.serializer(), SessionEnd()))
        ws.close(1000, "client requested")
        webSocket = null
    }

    private fun handleMessage(text: String, onEvent: (KiwiSessionEvent) -> Unit) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse {
                onEvent(KiwiSessionEvent.Error("invalid JSON from server"))
                return
            }
        when (obj.string("type")) {
            Protocol.TYPE_SESSION_READY -> onEvent(KiwiSessionEvent.SessionReady)
            Protocol.TYPE_AUDIO_OUTPUT -> {
                val data = obj.string("data") ?: return
                val bytes = runCatching { Base64.decode(data, Base64.NO_WRAP) }
                    .getOrNull() ?: return
                onEvent(KiwiSessionEvent.AudioOutput(bytes))
            }
            Protocol.TYPE_TRANSCRIPT_INPUT -> {
                obj.string("text")?.let { onEvent(KiwiSessionEvent.InputTranscript(it)) }
            }
            Protocol.TYPE_TRANSCRIPT_OUTPUT -> {
                obj.string("text")?.let { onEvent(KiwiSessionEvent.OutputTranscript(it)) }
            }
            Protocol.TYPE_RESPONSE_END -> onEvent(KiwiSessionEvent.ResponseEnd)
            Protocol.TYPE_SCENE_SET -> {
                val sceneObj = (obj["scene"] as? JsonObject) ?: run {
                    KLog.w(TAG, "scene.set without scene payload")
                    return
                }
                parseScene(sceneObj)?.let { onEvent(KiwiSessionEvent.SceneSet(it)) }
            }
            Protocol.TYPE_ERROR -> {
                val msg = obj.string("message") ?: "unknown error"
                onEvent(KiwiSessionEvent.Error(msg))
            }
            else -> KLog.w(TAG, "Unknown message type: ${obj.string("type")}")
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    /**
     * Parse a scene payload into the matching [Scene] variant.
     *
     * Returns null on unknown / malformed scenes so the existing
     * scene on screen stays untouched (better than crashing the
     * connection over a typo on the backend side).
     */
    private fun parseScene(scene: JsonObject): Scene? {
        return when (scene.string("type")) {
            "calendar" -> parseCalendarScene(scene)
            "video_list" -> parseVideoListScene(scene)
            "playlist_list" -> parsePlaylistListScene(scene)
            "video_player" -> parseVideoPlayerScene(scene)
            "browse_youtube" -> parseBrowseYouTubeScene(scene)
            else -> {
                KLog.w(TAG, "Unknown scene type: ${scene.string("type")}")
                null
            }
        }
    }

    private fun parseCalendarScene(scene: JsonObject): Scene.Calendar {
        val period = scene.string("period") ?: "today"
        val rawEvents = scene["events"] as? JsonArray ?: emptyList()
        val events = rawEvents.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CalendarEvent(
                title = obj.string("title") ?: "(sin título)",
                startsAt = obj.string("starts_at") ?: return@mapNotNull null,
                endsAt = obj.string("ends_at") ?: return@mapNotNull null,
                location = obj.string("location"),
                allDay = (obj["all_day"] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
            )
        }
        return Scene.Calendar(period = period, events = events)
    }

    private fun parseVideoListScene(scene: JsonObject): Scene.VideoList {
        val title = scene.string("title") ?: ""
        val raw = scene["videos"] as? JsonArray ?: emptyList()
        val videos = raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("video_id") ?: return@mapNotNull null
            VideoItem(
                videoId = id,
                title = obj.string("title") ?: "(sin título)",
                channel = obj.string("channel") ?: "",
                durationLabel = obj.string("duration"),
                thumbnailUrl = obj.string("thumbnail_url"),
            )
        }
        return Scene.VideoList(title = title, videos = videos)
    }

    private fun parsePlaylistListScene(scene: JsonObject): Scene.PlaylistList {
        val raw = scene["playlists"] as? JsonArray ?: emptyList()
        val playlists = raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("playlist_id") ?: return@mapNotNull null
            PlaylistItem(
                playlistId = id,
                title = obj.string("title") ?: "(sin título)",
                itemCount = (obj["item_count"] as? JsonPrimitive)
                    ?.contentOrNull?.toIntOrNull() ?: 0,
                thumbnailUrl = obj.string("thumbnail_url"),
            )
        }
        return Scene.PlaylistList(playlists = playlists)
    }

    private fun parseVideoPlayerScene(scene: JsonObject): Scene.VideoPlayer? {
        val id = scene.string("video_id") ?: return null
        return Scene.VideoPlayer(
            videoId = id,
            title = scene.string("title") ?: "",
            channel = scene.string("channel") ?: "",
        )
    }

    private fun parseBrowseYouTubeScene(scene: JsonObject): Scene.BrowseYouTube? {
        val url = scene.string("url") ?: return null
        return Scene.BrowseYouTube(url = url)
    }

    private companion object {
        const val TAG = "KiwiSession"
    }
}
