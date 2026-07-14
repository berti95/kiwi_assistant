package com.kiwi.assistant.network

import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.AlarmItem
import com.kiwi.assistant.ui.FactoidItem
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
import com.kiwi.assistant.ui.PostIt
import com.kiwi.assistant.ui.SpotifyResultItem
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.WeatherInfo
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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

        val weather = (root["weather"] as? JsonObject)?.let { obj ->
            val temp = (obj["temperature_c"] as? JsonPrimitive)
                ?.doubleOrNull ?: return@let null
            WeatherInfo(
                temperatureC = temp,
                description = obj.string("description").orEmpty(),
                icon = obj.string("icon") ?: "cloudy",
                tempMaxC = (obj["temp_max_c"] as? JsonPrimitive)?.doubleOrNull,
                tempMinC = (obj["temp_min_c"] as? JsonPrimitive)?.doubleOrNull,
                precipitationProbabilityMax =
                    (obj["precipitation_probability_max"] as? JsonPrimitive)?.intOrNull,
                sunrise = obj.string("sunrise")?.takeIf { it.isNotBlank() },
                sunset = obj.string("sunset")?.takeIf { it.isNotBlank() },
            )
        }

        val alarmsArr = root["alarms"] as? JsonArray ?: JsonArray(emptyList())
        val alarms = alarmsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val firesAtMs = (obj["fires_at_ms"] as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            AlarmItem(
                id = obj.string("id") ?: return@mapNotNull null,
                firesAtMs = firesAtMs,
                label = obj.string("label").orEmpty(),
            )
        }

        val postitsArr = root["postits"] as? JsonArray ?: JsonArray(emptyList())
        val postits = postitsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            PostIt(
                id = obj.string("id") ?: return@mapNotNull null,
                text = obj.string("text").orEmpty(),
                color = obj.string("color") ?: "yellow",
                createdMs = (obj["created_ms"] as? JsonPrimitive)
                    ?.longOrNull ?: 0L,
            )
        }

        val recentArr = root["recently_played"] as? JsonArray
            ?: JsonArray(emptyList())
        val recentlyPlayed = recentArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            SpotifyResultItem(
                uri = obj.string("uri") ?: return@mapNotNull null,
                title = obj.string("title").orEmpty(),
                artist = obj.string("artist").orEmpty(),
                albumArtUrl = obj.string("album_art_url"),
            )
        }

        val factoid = (root["factoid"] as? JsonObject)?.let { obj ->
            FactoidItem(
                date = obj.string("date") ?: return@let null,
                year = (obj["year"] as? JsonPrimitive)?.intOrNull ?: return@let null,
                text = obj.string("text") ?: return@let null,
            )
        }

        return HomeSnapshot(
            eventsToday = events,
            eventsTodayError = root.string("events_today_error"),
            todos = todos,
            nowPlaying = nowPlaying,
            weather = weather,
            alarms = alarms,
            postits = postits,
            recentlyPlayed = recentlyPlayed,
            factoid = factoid,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "HomeStatePoller"
    }
}
