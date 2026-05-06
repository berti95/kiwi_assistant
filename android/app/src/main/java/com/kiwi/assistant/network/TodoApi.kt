package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.TodoItem
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for tap-driven TODO mutations.
 *
 * Voice mutations go through the Gemini tools (todo_add / complete /
 * remove). When the user taps a TODO directly on the tablet, we hit
 * the dev-token-gated REST endpoints instead — same persistence
 * (GCS blob), no Gemini round-trip. Returns the updated list on
 * success or null on any failure.
 */
class TodoApi(
    private val baseUrl: String,
    private val devToken: String,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(id: String): List<TodoItem>? = post("$id/complete")

    suspend fun remove(id: String): List<TodoItem>? = post("$id/remove")

    /**
     * Cancel the active backend timer. Fire-and-forget — if the call
     * fails the timer expires on its own (current() drops expired
     * entries) so a stale "cuánto queda" answer is at most a few
     * minutes off.
     */
    suspend fun cancelTimer(): Boolean {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return false
        val url = "${baseUrl.trimEnd('/')}/api/timer/cancel?token=$devToken"
        val request = Request.Builder().url(url).post(EMPTY_BODY).build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            KLog.w(TAG, "POST $url failed: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun post(suffix: String): List<TodoItem>? {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return null
        val url = "${baseUrl.trimEnd('/')}/api/todos/$suffix?token=$devToken"
        val request = Request.Builder()
            .url(url)
            .post(EMPTY_BODY)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "POST $url returned ${response.code}")
                    return null
                }
                parseItems(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            KLog.w(TAG, "POST $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseItems(body: String): List<TodoItem>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull() ?: return null
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            TodoItem(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null,
                text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                completed = (obj["completed"] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
            )
        }
    }

    private companion object {
        const val TAG = "TodoApi"
        val EMPTY_BODY = "".toRequestBody("application/json".toMediaType())
    }
}
