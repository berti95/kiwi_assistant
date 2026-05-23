package com.kiwi.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Captura audio del micrófono en el formato que espera Gemini Live API:
 * PCM 16-bit, 16 kHz, mono. Cada chunk capturado se entrega via
 * `onChunk(ByteArray)` para que el caller lo codifique en base64 y lo envíe
 * por el WebSocket.
 *
 * El permiso RECORD_AUDIO debe estar concedido antes de llamar a `start()`.
 * `start()` no comprueba el permiso para evitar duplicación con la lógica
 * del Activity; si no está concedido, AudioRecord fallará con state
 * UNINITIALIZED y `start()` devolverá false.
 */
class AudioCaptureManager {

    private var job: Job? = null
    private var recorder: AudioRecord? = null

    // AudioRecord.read() is a blocking native call that doesn't respect
    // coroutine cancellation. If stop() fires while a read() is mid-flight
    // the coroutine still ships that final buffer to onChunk before the
    // loop notices recordingState changed — that ~50 ms of audio leaks to
    // Gemini Live AFTER we asked the mic to shut up, which is enough to
    // confuse VAD on the next turn. The flag short-circuits onChunk so a
    // late read() is silently dropped instead.
    @Volatile private var stopRequested = false

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onChunk: (ByteArray) -> Unit): Boolean {
        if (job?.isActive == true) return true
        stopRequested = false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "Invalid min buffer size: $minBuffer")
            return false
        }
        val bufferSize = (minBuffer * 2).coerceAtLeast(CHUNK_BYTES * 2)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize (permission missing?).")
            rec.release()
            return false
        }

        recorder = rec
        rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_BYTES)
            while (
                !stopRequested &&
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0 && !stopRequested) {
                    onChunk(buffer.copyOf(read))
                }
            }
        }
        return true
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
    }

    private companion object {
        const val TAG = "AudioCaptureManager"

        // Gemini Live expects 16 kHz PCM 16-bit mono on the input side.
        const val SAMPLE_RATE_HZ = 16_000

        // ~50 ms per chunk (16 kHz * 16 bit * 1 ch = 32_000 bytes/s ⇒
        // 1_600 bytes ≈ 50 ms). Small enough for low latency, large enough
        // to amortize per-message JSON overhead on the WebSocket.
        const val CHUNK_BYTES = 1_600
    }
}
