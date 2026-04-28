package com.kiwi.assistant.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Three-stage openWakeWord inference pipeline (mel → embedding → keyword).
 *
 * The classifier currently bundled is ``kiwi_variants.onnx``, trained
 * in Colab via ``tools/wakeword-training-es/`` (Piper TTS Spanish
 * voices for positives + the standard openWakeWord negatives +
 * augmentation). It fires on any of:
 *   • "hola kiwi"
 *   • "hey kiwi"
 *   • "oye kiwi"
 *   • "eh kiwi"
 * Mirrors the reference Python implementation (dscripka/openWakeWord)
 * so dropping in any other pre-trained ``.onnx`` (and updating the
 * ``loadAsset`` call below) works without code changes:
 *
 *   1. **Mel spectrogram** model converts a chunk of raw 16 kHz PCM
 *      audio into 32-band mel feature frames. We normalise in place
 *      with the openWakeWord convention ``x / 10 + 2``.
 *   2. **Embedding** model (Google's speech embedding network)
 *      consumes a 76-frame window of mel features and emits a 96-dim
 *      embedding. The sliding stride is 8 mel frames, which gives
 *      one new embedding roughly every 80 ms.
 *   3. **Wake word** model takes the most recent N embeddings (N is
 *      whatever the wake word model was trained for; we read it from
 *      the ONNX input shape at load time, so different ``.onnx``
 *      files keep working) and returns the wake-phrase probability.
 *
 * Single-threaded — the buffers and ONNX sessions are not safe for
 * concurrent feeders. The wake-word listener owns one detector and
 * feeds it from a single audio thread.
 */
class WakeWordDetector(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val melSession: OrtSession =
        env.createSession(loadAsset(context, "wakeword/melspectrogram.onnx"))
    private val embeddingSession: OrtSession =
        env.createSession(loadAsset(context, "wakeword/embedding_model.onnx"))
    private val wakewordSession: OrtSession =
        env.createSession(loadAsset(context, "wakeword/kiwi_variants.onnx"))

    private val melInputName: String = melSession.inputNames.first()
    private val embeddingInputName: String = embeddingSession.inputNames.first()
    private val wakewordInputName: String = wakewordSession.inputNames.first()

    /**
     * How many embeddings the wake-word model expects. Read at load
     * time from the model's input shape (typically 16 for
     * ``hey_jarvis_v0.1``) so different openWakeWord ``.onnx`` files
     * with different time windows still work.
     */
    private val wakewordWindow: Int = run {
        val info = wakewordSession.inputInfo[wakewordInputName]
        val shape = (info?.info as? TensorInfo)?.shape
            ?: longArrayOf(1, 16, EMBEDDING_DIM.toLong())
        shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: 16
    }.also { Log.i(TAG, "Wake-word model window: $it embeddings") }

    private val melBuffer = ArrayDeque<FloatArray>()  // each entry = 32 mel bins
    private val embeddingBuffer = ArrayDeque<FloatArray>()  // each = 96 floats

    /**
     * How many embeddings to keep around. We never look further back
     * than [wakewordWindow] when building the wake-word input, so the
     * cap is `wakewordWindow + EMBEDDING_BUFFER_HEADROOM`. Hard-coding a
     * smaller absolute cap (the previous behaviour) silently broke any
     * future ``.onnx`` whose window happened to exceed it — e.g. a
     * custom model trained on a longer phrase.
     */
    private val embeddingBufferCap: Int =
        wakewordWindow + EMBEDDING_BUFFER_HEADROOM

    @Volatile
    var lastScore: Float = 0f
        private set

    /**
     * Feed a chunk of raw 16 kHz mono PCM 16-bit samples. 1280 samples
     * (80 ms) is the sweet spot recommended by openWakeWord, but other
     * sizes work — the pipeline buffers internally. Returns the latest
     * wake-word probability if this feed produced one, else null.
     */
    fun feed(samples: ShortArray): Float? {
        if (samples.isEmpty()) return null

        // 1. Audio → mel features.
        val audioFloats = FloatArray(samples.size) { samples[it].toFloat() }
        val melFrames = runMelspectrogram(audioFloats)
        for (frame in melFrames) {
            // openWakeWord normalisation: x/10 + 2.
            for (i in frame.indices) frame[i] = frame[i] / 10f + 2f
            melBuffer.addLast(frame)
            // Defensive cap so a long quiet stretch can't grow this
            // unboundedly if the embedding loop below ever skipped.
            while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()
        }

        // 2. Mel features → embeddings, one per 8-frame stride once we
        // have a full 76-frame window. Drop the consumed stride after
        // each embedding so the buffer doesn't grow.
        var newEmbeddings = false
        while (melBuffer.size >= MEL_WINDOW) {
            val embedding = runEmbedding()
            embeddingBuffer.addLast(embedding)
            while (embeddingBuffer.size > embeddingBufferCap) embeddingBuffer.removeFirst()
            repeat(EMBEDDING_HOP) { melBuffer.removeFirst() }
            newEmbeddings = true
        }

        // 3. Embeddings → wake-word probability, but only when we have
        // enough embeddings to fill the model's window AND we actually
        // produced new ones this round (otherwise the score wouldn't
        // change so re-running is pointless).
        if (!newEmbeddings || embeddingBuffer.size < wakewordWindow) return null
        val score = runWakeword()
        lastScore = score
        return score
    }

    // ---- ONNX helpers --------------------------------------------------
    //
    // We read outputs via OnnxTensor.floatBuffer (a flat view of the
    // tensor data) rather than .value (which builds a Java multi-dim
    // array via reflection). Two reasons:
    //   1. Robustness — the shape varies between openWakeWord model
    //      builds (the embedding model is sometimes (1, 96), sometimes
    //      (1, 1, 1, 96)). A fixed `as Array<...>` cast would
    //      ClassCastException; reading the buffer flat handles either.
    //   2. Slightly less garbage per inference because the multi-dim
    //      array allocation is skipped.

    private fun runMelspectrogram(audio: FloatArray): List<FloatArray> {
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audio),
            longArrayOf(1L, audio.size.toLong()),
        )
        return tensor.use {
            melSession.run(mapOf(melInputName to it)).use { result ->
                val out = result[0] as OnnxTensor
                // Output shape is (1, frames, 32). Read the frame
                // count from the tensor's actual shape so the model
                // is allowed to choose how many frames to emit.
                val shape = out.info.shape
                val frames = (shape.getOrNull(shape.size - 2) ?: 0L).toInt()
                if (frames <= 0) return@use emptyList()
                val flat = FloatArray(frames * MEL_BINS)
                out.floatBuffer.get(flat)
                List(frames) { f ->
                    FloatArray(MEL_BINS) { b -> flat[f * MEL_BINS + b] }
                }
            }
        }
    }

    private fun runEmbedding(): FloatArray {
        // Build a (1, 76, 32, 1) float tensor from the head of the
        // mel buffer. The 8-frame stride is applied by the caller
        // (which removes the first 8 frames after this call returns).
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        for (i in 0 until MEL_WINDOW) {
            val row = melBuffer[i]
            System.arraycopy(row, 0, flat, i * MEL_BINS, MEL_BINS)
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1L, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1L),
        )
        return tensor.use {
            embeddingSession.run(mapOf(embeddingInputName to it)).use { result ->
                val out = result[0] as OnnxTensor
                // Output is 96 floats per batch element, possibly
                // wrapped in unit dimensions. Reading the flat buffer
                // gives us 96 floats regardless of shape.
                val embedding = FloatArray(EMBEDDING_DIM)
                out.floatBuffer.get(embedding)
                embedding
            }
        }
    }

    private fun runWakeword(): Float {
        val flat = FloatArray(wakewordWindow * EMBEDDING_DIM)
        val start = embeddingBuffer.size - wakewordWindow
        for (i in 0 until wakewordWindow) {
            val emb = embeddingBuffer[start + i]
            System.arraycopy(emb, 0, flat, i * EMBEDDING_DIM, EMBEDDING_DIM)
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1L, wakewordWindow.toLong(), EMBEDDING_DIM.toLong()),
        )
        return tensor.use {
            wakewordSession.run(mapOf(wakewordInputName to it)).use { result ->
                val out = result[0] as OnnxTensor
                val buffer = out.floatBuffer
                // openWakeWord wake-word models output a single
                // probability — but some derivative trainers ship
                // 2-class logits instead, with the positive-class
                // probability at index 1. Take the last element so
                // either layout works.
                val outputs = FloatArray(buffer.remaining())
                buffer.get(outputs)
                outputs.last()
            }
        }
    }

    private fun loadAsset(context: Context, path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    override fun close() {
        runCatching { wakewordSession.close() }
        runCatching { embeddingSession.close() }
        runCatching { melSession.close() }
        // Don't close OrtEnvironment — it's a process-wide singleton
        // shared with SpeechActivityDetector.
        Log.i(TAG, "WakeWordDetector closed")
    }

    private companion object {
        const val TAG = "WakeWordDetector"
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMBEDDING_HOP = 8
        const val EMBEDDING_DIM = 96
        const val MEL_BUFFER_MAX = 200
        // A few embeddings of headroom on top of the model's own
        // window — keeps memory bounded while still surviving a small
        // burst of mel frames produced from a single feed() call.
        const val EMBEDDING_BUFFER_HEADROOM = 8
    }
}
