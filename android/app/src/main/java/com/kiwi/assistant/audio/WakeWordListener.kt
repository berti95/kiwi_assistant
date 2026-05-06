package com.kiwi.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.kiwi.assistant.log.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the microphone while the app is in the Idle state and listens
 * continuously for the wake word, firing [onDetected] when openWakeWord
 * crosses the threshold for [debounceFrames] consecutive ~80 ms windows.
 *
 * Audio resource model: only one [AudioRecord] can be active for the
 * same source at a time, so the ViewModel must call [stop] before
 * starting [AudioCaptureManager] for a Kiwi session, and call [start]
 * again once the session ends.
 *
 * Lifecycle: detector + recorder are created on [start] and released
 * on [stop]. The detector's ONNX models load ~3 MB on first start;
 * subsequent starts re-load them but it's still <100 ms on the
 * Pixel Tablet so we keep the lifecycle simple.
 */
class WakeWordListener(
    private val context: Context,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val debounceFrames: Int = DEFAULT_DEBOUNCE_FRAMES,
) {

    private var job: Job? = null
    private var recorder: AudioRecord? = null
    private var detector: WakeWordDetector? = null

    @Volatile private var stopRequested = false

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onDetected: () -> Unit): Boolean {
        if (job?.isActive == true) return true
        stopRequested = false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            KLog.w(TAG, "Invalid min buffer size: $minBuffer")
            return false
        }
        // Read in 80 ms chunks (1280 samples) — openWakeWord's
        // recommended hop. Allocate the AudioRecord with a generous
        // OS-side buffer so a brief GC pause doesn't drop frames.
        val bufferSize = (minBuffer * 4).coerceAtLeast(CHUNK_SAMPLES * 2 * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            KLog.w(TAG, "AudioRecord failed to initialize (permission missing?).")
            rec.release()
            return false
        }

        val det = try {
            WakeWordDetector(context)
        } catch (e: Exception) {
            KLog.e(TAG, "Failed to initialise WakeWordDetector", e)
            rec.release()
            return false
        }

        recorder = rec
        detector = det
        rec.startRecording()
        KLog.i(TAG, "Wake-word listener started (threshold=$threshold)")

        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)
            var aboveThreshold = 0
            var peakScore = 0f
            var lastPeakLogMs = System.currentTimeMillis()
            try {
                while (
                    !stopRequested &&
                    rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0 || stopRequested) continue

                    val score = det.feed(buffer.copyOf(read))
                    if (score == null) continue

                    if (score > peakScore) peakScore = score
                    // Log the peak score every PEAK_LOG_INTERVAL_MS so
                    // we can tell whether the model is producing
                    // anything sensible even when the threshold isn't
                    // being crossed — the alternative is per-frame
                    // logging which is way too chatty.
                    val now = System.currentTimeMillis()
                    if (now - lastPeakLogMs >= PEAK_LOG_INTERVAL_MS) {
                        KLog.i(TAG, "peak score in last ${(now - lastPeakLogMs) / 1000}s: $peakScore")
                        peakScore = 0f
                        lastPeakLogMs = now
                    }

                    if (score >= threshold) {
                        aboveThreshold += 1
                        if (aboveThreshold >= debounceFrames) {
                            KLog.i(TAG, "Wake word DETECTED (score=$score)")
                            // Release the mic + ONNX sessions BEFORE
                            // notifying the caller so the session
                            // capture path can immediately acquire the
                            // AudioRecord without racing us. After this
                            // point the listener is effectively stopped
                            // even if the caller doesn't explicitly
                            // call stop().
                            releaseRecorderAndDetector()
                            // Fire on the main thread so the
                            // ViewModel can mutate state safely.
                            withContext(Dispatchers.Main) {
                                if (!stopRequested) onDetected()
                            }
                            break
                        }
                    } else {
                        aboveThreshold = 0
                    }
                }
            } catch (e: CancellationException) {
                // Normal shutdown via stop() (or via the caller's
                // viewModelScope being cancelled). Re-throw so the
                // coroutine machinery can settle the parent job.
                throw e
            } catch (e: Exception) {
                KLog.e(TAG, "Wake-word loop crashed", e)
            } finally {
                // Idempotent: if we hit detection above, this is a no-op.
                releaseRecorderAndDetector()
                KLog.i(TAG, "Wake-word loop exiting")
            }
        }
        return true
    }

    /** Release mic + ONNX sessions if they're still around. Idempotent. */
    @Synchronized
    private fun releaseRecorderAndDetector() {
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        // Defensive cleanup: in the normal flow the coroutine releases
        // the recorder and detector itself, but if an external caller
        // (the ViewModel's onCleared, an error path, or a tap-to-open
        // before any detection) gets here first we still need to
        // tear them down.
        releaseRecorderAndDetector()
    }

    private companion object {
        const val TAG = "WakeWordListener"
        const val SAMPLE_RATE_HZ = 16_000
        // 80 ms at 16 kHz = 1280 samples — the openWakeWord-recommended
        // hop. Anything else still works (the detector buffers) but
        // this minimises end-to-end latency.
        const val CHUNK_SAMPLES = 1_280
        // Probability above which we count the frame as a "yes". The
        // openWakeWord README suggests 0.5 as a sane default; we run
        // a notch lower because in practice 0.5 was missing too many
        // legitimate "hola/hey/oye/eh kiwi" especially when the user
        // is more than ~1 m from the dock or speaks softly. The
        // 2-frame debounce below already filters out single-frame
        // spikes, so dropping the threshold without raising the
        // debounce keeps false positives in check.
        const val DEFAULT_THRESHOLD = 0.4f
        // Require this many consecutive yes-frames before firing, to
        // avoid spurious triggers. 2 × 80 ms = 160 ms of sustained
        // detection — short enough to feel snappy.
        const val DEFAULT_DEBOUNCE_FRAMES = 2
        // How often to summarise the peak wake-word score in the log
        // when no detection has fired. Useful for tuning the threshold
        // and for sanity-checking that the model is producing scores
        // at all.
        const val PEAK_LOG_INTERVAL_MS = 5_000L
    }
}
