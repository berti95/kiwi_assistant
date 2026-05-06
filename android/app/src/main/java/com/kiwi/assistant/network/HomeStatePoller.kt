package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
import com.kiwi.assistant.ui.TodoItem
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pulls the home dashboard snapshot from the backend.
 *
 * Used by the ViewModel to refresh the Idle/Home screen periodically
 * (and on demand after a tool call updates a TODO). Returns ``null``
 * on any failure — networking, auth, or malformed JSON — so the
 * caller can decide whether to keep the previous snapshot on screen.
 */
class HomeStatePoller(
    private val baseUrl: String,
    private val devToken: String,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchOnce(): HomeSnapshot? {
        if (baseUrl.isEmpty() || devToken.isEmpty()) return null
        val url = "${baseUrl.trimEnd('/')}/api/home?token=$devToken"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    KLog.w(TAG, "home GET ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                parse(body)
            }
        } catch (e: Exception) {
            KLog.w(TAG, "home GET failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parse(body: String): HomeSnapshot? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull() ?: return null

        val eventsArr = root["events_today"] as? JsonArray ?: JsonArray(emptyList())
        val events = eventsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            CalendarEvent(
                title = obj.string("title") ?: return@mapNotNull null,
                startsAt = obj.string("starts_at") ?: return@mapNotNull null,
                endsAt = obj.string("ends_at") ?: return@mapNotNull null,
                location = obj.string("location"),
                allDay = (obj["all_day"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        val todosArr = root["todos"] as? JsonArray ?: JsonArray(emptyList())
        val todos = todosArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            TodoItem(
                id = obj.string("id") ?: return@mapNotNull null,
                text = obj.string("text").orEmpty(),
                completed = (obj["completed"] as? JsonPrimitive)
                    ?.booleanOrNull ?: false,
            )
        }

        val nowPlaying = (root["now_playing"] as? JsonObject)?.let { obj ->
            NowPlayingChip(
                title = obj.string("title").orEmpty(),
                artist = obj.string("artist").orEmpty(),
                albumArtUrl = obj.string("album_art_url"),
            )
        }

        return HomeSnapshot(
            eventsToday = events,
            todos = todos,
            nowPlaying = nowPlaying,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "HomeStatePoller"
    }
}
