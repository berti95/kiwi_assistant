package com.kiwi.assistant.audio

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Wraps Silero VAD (gkonovalov/android-vad) so the rest of the app can
 * ask "did the user actually speak in this turn?" without caring about
 * Silero's frame-size constraints.
 *
 * The VAD only accepts buffers of an exact size (512, 1024, or 1536
 * samples at 16 kHz). Our [AudioCaptureManager] produces ~50 ms chunks
 * of 1600 bytes (800 samples), which doesn't match any of those, so we
 * keep an internal buffer that re-frames the incoming bytes into
 * Silero-friendly 1024-byte (512-sample, 32 ms) frames before feeding
 * them in. Bytes that don't fill a complete frame stay buffered for
 * the next call.
 *
 * Usage per turn:
 *   detector.reset()                          // before opening the mic
 *   for each capture chunk: detector.feed(chunk)
 *   if (detector.userSpoke) ...               // after the user taps to end
 *
 * The detector lives for the whole session; it's only [close]d when the
 * ViewModel is cleared.
 */
class SpeechActivityDetector(context: Context) : AutoCloseable {

    private val vad: VadSilero = VadSilero(
        context = context.applicationContext,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        // NORMAL is the recommended trade-off; AGGRESSIVE is too eager
        // for indoor speech and OFF picks up too much background noise.
        mode = Mode.NORMAL,
        // 300 ms of silence ends a speech segment; 50 ms of voice opens
        // one. These are Silero's tuned defaults for conversational
        // speech.
        silenceDurationMs = 300,
        speechDurationMs = 50,
    )

    private val frameBytes = FRAME_SAMPLES * BYTES_PER_SAMPLE
    private val buffer = ByteArray(frameBytes)
    private var buffered = 0

    @Volatile
    var userSpoke: Boolean = false
        private set

    /**
     * Wall-clock timestamp (ms) of the last frame Silero classified as
     * speech. Combined with [userSpoke], this lets the ViewModel decide
     * the user has finished their turn after a configurable silence
     * threshold — see [isEndOfTurn].
     */
    @Volatile
    private var lastSpeechMs: Long = 0

    /** Drop any pending audio and reset the per-turn flag. */
    @Synchronized
    fun reset() {
        buffered = 0
        userSpoke = false
        lastSpeechMs = 0
    }

    /**
     * Append [chunk] to the internal buffer and run VAD over every
     * complete frame that's now available. If any of those frames is
     * classified as speech, [userSpoke] flips to true for the rest of
     * the turn (until the next [reset]).
     */
    @Synchronized
    fun feed(chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val toCopy = minOf(frameBytes - buffered, chunk.size - offset)
            System.arraycopy(chunk, offset, buffer, buffered, toCopy)
            buffered += toCopy
            offset += toCopy

            if (buffered == frameBytes) {
                try {
                    if (vad.isSpeech(buffer)) {
                        userSpoke = true
                        lastSpeechMs = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    // Don't let a VAD glitch kill capture; just log
                    // and keep going. Worst case userSpoke stays
                    // false and we drop a turn that would've gone
                    // through anyway.
                    Log.w(TAG, "Silero VAD failed on frame", e)
                }
                buffered = 0
            }
        }
    }

    /**
     * True if the user has spoken at some point during the current
     * turn AND has been silent for at least [silenceThresholdMs] since
     * the last detected speech frame. Use this to auto-end the turn
     * the way mainstream voice agents do, instead of forcing the user
     * to tap a stop button.
     *
     * Silero already smooths short pauses internally (its
     * `silenceDurationMs` parameter, 300 ms in our config), so the
     * effective end-of-turn delay perceived by the user is roughly
     * [silenceThresholdMs] + 300 ms.
     */
    fun isEndOfTurn(silenceThresholdMs: Long): Boolean {
        if (!userSpoke) return false
        val sinceLast = System.currentTimeMillis() - lastSpeechMs
        return sinceLast >= silenceThresholdMs
    }

    override fun close() {
        runCatching { vad.close() }
    }

    private companion object {
        const val TAG = "SpeechActivityDetector"

        // Silero at 16 kHz accepts 512, 1024 or 1536 sample frames; 512
        // is the recommended size and gives us 32 ms granularity.
        const val FRAME_SAMPLES = 512
        const val BYTES_PER_SAMPLE = 2  // PCM 16-bit
    }
}
