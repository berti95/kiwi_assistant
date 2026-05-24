package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.UsageDay
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.UsageToolCount
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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

    /** Mark a shopping item as bought (server returns updated list). */
    suspend fun completeShopping(id: String): List<ShoppingItemDto>? =
        postShopping("$id/complete")

    /** Remove a shopping item outright. */
    suspend fun removeShopping(id: String): List<ShoppingItemDto>? =
        postShopping("$id/remove")

    /**
     * GET la lista actual de la compra. Usado por la home cuando el
     * usuario abre Compra desde la quick-actions row. Null si la red
     * falla o el token no es válido — el caller pinta vacío.
     */
    suspend fun fetchShoppingList(): List<ShoppingItemDto>? {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return null
        val url = "${baseUrl.trimEnd('/')}/api/shopping?token=$devToken"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "GET $url returned ${response.code}")
                    return null
                }
                parseShoppingItems(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            KLog.w(TAG, "GET $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /** Tipo intermedio para no introducir circular dep con ui.* aquí. */
    data class ShoppingItemDto(val id: String, val text: String, val completed: Boolean)

    private fun postShopping(suffix: String): List<ShoppingItemDto>? {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return null
        val url = "${baseUrl.trimEnd('/')}/api/shopping/$suffix?token=$devToken"
        val request = Request.Builder().url(url).post(EMPTY_BODY).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "POST $url returned ${response.code}")
                    return null
                }
                parseShoppingItems(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            KLog.w(TAG, "POST $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseShoppingItems(body: String): List<ShoppingItemDto>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull() ?: return null
        val arr = root["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            ShoppingItemDto(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null,
                text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                completed = (obj["completed"] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
            )
        }
    }

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

    suspend fun dismissAlarm(id: String): Boolean = simplePost(
        "/api/alarms/$id/dismiss",
    )

    suspend fun snoozeAlarm(id: String, minutes: Int): Boolean = simplePost(
        "/api/alarms/$id/snooze?minutes=$minutes",
    )

    /**
     * GET /api/stats con un periodo. Devuelve directamente
     * [Scene.UsageStats] para que el ViewModel sólo tenga que
     * empujarla a la escena. Null si la red falla o el JSON
     * no parsea — el caller decide qué mostrar (típicamente:
     * la escena con conversation_count=0).
     */
    suspend fun fetchUsageStats(period: String = "today"): Scene.UsageStats? {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return null
        val url = "${baseUrl.trimEnd('/')}/api/stats?period=$period&token=$devToken"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "GET $url returned ${response.code}")
                    return null
                }
                parseUsageStats(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            KLog.w(TAG, "GET $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseUsageStats(body: String): Scene.UsageStats? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull() ?: return null
        fun double(key: String, default: Double = 0.0): Double =
            (root[key] as? JsonPrimitive)?.doubleOrNull ?: default
        fun int(key: String, default: Int = 0): Int =
            (root[key] as? JsonPrimitive)?.intOrNull ?: default
        val toolsArr = root["top_tools"] as? JsonArray ?: JsonArray(emptyList())
        val tools = toolsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            UsageToolCount(
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null,
                count = (obj["count"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        val byDayArr = root["by_day"] as? JsonArray ?: JsonArray(emptyList())
        val byDay = byDayArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            UsageDay(
                date = (obj["date"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null,
                costEur = (obj["cost_eur"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
        return Scene.UsageStats(
            period = (root["period"] as? JsonPrimitive)?.contentOrNull ?: "today",
            conversationCount = int("conversation_count"),
            turnCount = int("turn_count"),
            audioInSeconds = double("audio_in_seconds"),
            audioOutSeconds = double("audio_out_seconds"),
            audioTotalSeconds = double("audio_total_seconds"),
            estimatedCostEur = double("estimated_cost_eur"),
            topTools = tools,
            byDay = byDay,
        )
    }

    private fun simplePost(pathAndQuery: String): Boolean {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return false
        val sep = if ('?' in pathAndQuery) '&' else '?'
        val url = "${baseUrl.trimEnd('/')}$pathAndQuery${sep}token=$devToken"
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
