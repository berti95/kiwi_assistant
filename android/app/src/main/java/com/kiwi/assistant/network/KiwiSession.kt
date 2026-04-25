package com.kiwi.assistant.network

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

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

    private val json = Json { ignoreUnknownKeys = true }
    private var webSocket: WebSocket? = null

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
                Log.w(TAG, "WebSocket failure", t)
                onEvent(KiwiSessionEvent.Error(t.message ?: "WebSocket failure"))
            }
        })
    }

    fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        val data = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws.send(json.encodeToString(AudioInput.serializer(), AudioInput(data = data)))
    }

    fun sendAudioEnd() {
        val ws = webSocket ?: return
        ws.send(json.encodeToString(AudioEnd.serializer(), AudioEnd()))
    }

    fun close() {
        val ws = webSocket ?: return
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
            Protocol.TYPE_ERROR -> {
                val msg = obj.string("message") ?: "unknown error"
                onEvent(KiwiSessionEvent.Error(msg))
            }
            else -> Log.w(TAG, "Unknown message type: ${obj.string("type")}")
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "KiwiSession"
    }
}
