package com.kiwi.assistant.log

import com.kiwi.assistant.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Periodically POSTs queued [KLog] entries to the backend so the
 * developer can see what the tablet is doing without ADB.
 *
 * Failures (network down, server 5xx, unauthorised) re-queue the
 * batch at the head of the buffer for the next attempt — preserves
 * causal order and survives transient outages.
 *
 * Uses ``android.util.Log`` directly (not [KLog]) for its OWN
 * messages, otherwise a network failure here would feed the buffer
 * faster than we drain it.
 */
class LogShipper(
    private val baseUrl: String,
    private val apiKey: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    private var job: Job? = null

    @Serializable
    private data class WireEntry(
        val ts_ms: Long,
        val level: String,
        val tag: String,
        val message: String,
        val error: String? = null,
    )

    @Serializable
    private data class WireBatch(
        val device_id: String,
        val version_code: Int,
        val version_name: String,
        val entries: List<WireEntry>,
    )

    fun start(scope: CoroutineScope, deviceId: String = DEFAULT_DEVICE_ID) {
        if (job?.isActive == true) return
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            android.util.Log.w(
                TAG,
                "not starting: baseUrl/apiKey missing (BuildConfig misconfigured?)",
            )
            return
        }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                runOnce(deviceId)
            }
        }
        android.util.Log.i(TAG, "started (interval=${intervalMs}ms, batch=$batchSize)")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun runOnce(deviceId: String) {
        val drained = KLog.drain(batchSize) ?: return
        val batch = WireBatch(
            device_id = deviceId,
            version_code = BuildConfig.VERSION_CODE,
            version_name = BuildConfig.VERSION_NAME,
            entries = drained.map {
                WireEntry(it.tsMs, it.level, it.tag, it.message, it.error)
            },
        )
        val body = json.encodeToString(WireBatch.serializer(), batch)
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = "${baseUrl.trimEnd('/')}/api/logs"
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(
                        TAG,
                        "POST $url returned ${response.code}, requeueing ${drained.size} entries",
                    )
                    KLog.requeue(drained)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "POST $url failed: ${e::class.simpleName}: ${e.message}, requeueing ${drained.size} entries",
            )
            KLog.requeue(drained)
        }
    }

    private companion object {
        const val TAG = "LogShipper"
        const val DEFAULT_DEVICE_ID = "kiwi-tablet"
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val DEFAULT_BATCH_SIZE = 200
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
