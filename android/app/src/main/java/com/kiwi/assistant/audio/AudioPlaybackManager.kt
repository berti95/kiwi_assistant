package com.kiwi.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Reproduce audio recibido de Gemini Live API: PCM 16-bit, 24 kHz, mono.
 *
 * `AudioTrack` se crea en modo STREAM y se va alimentando con `play(chunk)`
 * según llegan los frames del WebSocket. La primera vez que escribimos
 * arrancamos la reproducción; cuando `stop()` se llama, vaciamos el buffer
 * y liberamos el track.
 */
class AudioPlaybackManager {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "Invalid min buffer size: $minBuffer")
            return
        }
        val bufferSize = (minBuffer * 4).coerceAtLeast(MIN_BUFFER_BYTES)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    fun play(chunk: ByteArray) {
        val current = track ?: run {
            start()
            track ?: return
        }
        runCatching {
            current.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        }.onFailure {
            Log.w(TAG, "AudioTrack.write failed; recreating", it)
            runCatching { current.release() }
            track = null
        }
    }

    /** Drains the buffer and releases the underlying AudioTrack. */
    fun stop() {
        track?.let {
            runCatching {
                it.stop()
                it.release()
            }.onFailure { e -> Log.w(TAG, "AudioTrack stop/release failed", e) }
        }
        track = null
    }

    private companion object {
        const val TAG = "AudioPlaybackManager"

        // Gemini Live emits 24 kHz PCM 16-bit mono on the response side.
        const val SAMPLE_RATE_HZ = 24_000

        // Lower bound so the AudioTrack never starves while WebSocket
        // frames are still arriving (covers ~1s of audio: 24k * 2 = 48k).
        const val MIN_BUFFER_BYTES = 48_000

        @Suppress("unused")
        const val USAGE = AudioManager.STREAM_MUSIC
    }
}
