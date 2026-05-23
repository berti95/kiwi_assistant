package com.kiwi.assistant.log

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Drop-in replacement for [android.util.Log] that ALSO buffers each
 * line so the [LogShipper] can POST it to the backend.
 *
 * Use this instead of `android.util.Log.X(...)` everywhere we want
 * the developer to see what's going on remotely (ViewModel state
 * transitions, network events, tool dispatches, …). Anything that
 * stays inside `android.util.Log` won't reach Cloud Logging — and
 * reaching the tablet vía ADB en producción es engorroso, así que
 * preferimos el camino remoto.
 *
 * Buffer is bounded ([MAX_BUFFER]) so a network outage can't blow up
 * heap; the oldest entries get dropped first. The shipper drains in
 * insertion order so causality is preserved as long as we ship faster
 * than we generate.
 */
object KLog {
    /** One line of structured log, ready to be shipped. */
    data class Entry(
        val tsMs: Long,
        val level: String,
        val tag: String,
        val message: String,
        val error: String? = null,
    )

    private const val MAX_BUFFER = 2_000

    // ConcurrentLinkedDeque so the shipper can push entries back at
    // the head if the POST fails, preserving the original order on
    // the wire.
    private val buffer = ConcurrentLinkedDeque<Entry>()

    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
        add("V", tag, msg, null)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        add("W", tag, msg, t?.stackTraceToString())
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        add("E", tag, msg, t?.stackTraceToString())
    }

    private fun add(level: String, tag: String, message: String, error: String?) {
        buffer.add(Entry(System.currentTimeMillis(), level, tag, message, error))
        // Trim from the head so the freshest signal survives.
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()
    }

    /**
     * Drain up to [max] entries for shipping. Returns null when the
     * buffer is empty so the caller can short-circuit a POST.
     */
    fun drain(max: Int): List<Entry>? {
        if (buffer.isEmpty()) return null
        val out = ArrayList<Entry>(max.coerceAtMost(buffer.size))
        while (out.size < max) {
            val e = buffer.pollFirst() ?: break
            out.add(e)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Push entries back at the head in original order. Called by the
     * shipper when a POST fails so we can retry on the next cycle
     * without dropping data.
     */
    fun requeue(entries: List<Entry>) {
        // addFirst reverses ordering, so iterate the input in reverse.
        for (i in entries.indices.reversed()) {
            buffer.addFirst(entries[i])
        }
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()
    }

    /** Test helper. */
    internal fun bufferSizeForTest(): Int = buffer.size
}
