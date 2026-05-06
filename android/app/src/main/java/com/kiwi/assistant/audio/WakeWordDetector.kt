package com.kiwi.assistant.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.kiwi.assistant.log.KLog
import java.nio.FloatBuffer

/**
 * Three-stage openWakeWord inference pipeline (mel → embedding → keyword).
 *
 * Two classifier heads run on every embedding window and the
 * highest-scoring one wins:
 *   • ``kiwi_variants.onnx`` — custom-trained Spanish phrases
 *     ("hola/hey/oye/eh kiwi"), built from Piper TTS positives in
 *     ``tools/wakeword-training-es/``.
 *   • ``alexa_v0.1.onnx`` — pre-trained openWakeWord model for
 *     "alexa" / "hey alexa". Ships well-tuned out of the box; the
 *     user already has the muscle memory from their Echo device.
 *
 * Mel + embedding stages are shared (the heavy ones), so adding a
 * second classifier head is a few KB / ms of extra cost. Adding
 * more is a single line in [WAKEWORD_MODELS].
 *
 * Pipeline:
 *   1. **Mel spectrogram** model converts a chunk of raw 16 kHz PCM
 *      audio into 32-band mel feature frames. Normalised in place
 *      with the openWakeWord convention ``x / 10 + 2``.
 *   2. **Embedding** model (Google's speech embedding network)
 *      consumes a 76-frame window of mel features and emits a 96-dim
 *      embedding. Sliding stride is 8 mel frames → roughly one new
 *      embedding every 80 ms.
 *   3. **Wake word** models take the most recent N embeddings (read
 *      from each ``.onnx`` input shape at load time, so different
 *      models with different time windows still work) and return a
 *      wake-phrase probability.
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

    private val melInputName: String = melSession.inputNames.first()
    private val embeddingInputName: String = embeddingSession.inputNames.first()

    /**
     * One classifier head — name + ONNX session + input tensor name +
     * how many embeddings the model expects in its input window.
     */
    private data class WakewordModel(
        val name: String,
        val session: OrtSession,
        val inputName: String,
        val window: Int,
    )

    private val models: List<WakewordModel> = WAKEWORD_MODELS.map { (name, asset) ->
        val session = env.createSession(loadAsset(context, asset))
        val inputName = session.inputNames.first()
        val info = session.inputInfo[inputName]
        val shape = (info?.info as? TensorInfo)?.shape
            ?: longArrayOf(1, 16, EMBEDDING_DIM.toLong())
        val window = shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: 16
        KLog.i(TAG, "loaded wake-word model '$name' (window=$window)")
        WakewordModel(name, session, inputName, window)
    }

    /** All models share the same embedding buffer; cap once for the worst case. */
    private val embeddingBufferCap: Int =
        (models.maxOfOrNull { it.window } ?: 16) + EMBEDDING_BUFFER_HEADROOM

    private val melBuffer = ArrayDeque<FloatArray>()  // each entry = 32 mel bins
    private val embeddingBuffer = ArrayDeque<FloatArray>()  // each = 96 floats

    /**
     * Score for each model from the last [feed] that produced one.
     * Defaults to 0 for all models. Useful for debugging "why didn't
     * it fire" via the periodic peak-score log.
     */
    @Volatile
    var lastScores: Map<String, Float> = models.associate { it.name to 0f }
        private set

    /** Backwards-compat single value — the highest of [lastScores]. */
    val lastScore: Float
        get() = lastScores.values.maxOrNull() ?: 0f

    /**
     * Feed a chunk of raw 16 kHz mono PCM 16-bit samples. 1280 samples
     * (80 ms) is the sweet spot recommended by openWakeWord, but other
     * sizes work — the pipeline buffers internally. Returns the
     * highest of the per-model scores if this feed produced any, else
     * null.
     */
    fun feed(samples: ShortArray): Scores? {
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

        // 3. Embeddings → wake-word probability per model. Skip when
        // we don't have any new embeddings (the score wouldn't change).
        if (!newEmbeddings) return null
        val scores = mutableMapOf<String, Float>()
        for (model in models) {
            if (embeddingBuffer.size < model.window) {
                scores[model.name] = 0f
                continue
            }
            scores[model.name] = runWakeword(model)
        }
        if (scores.isEmpty()) return null
        lastScores = scores
        return Scores(scores)
    }

    /** Per-model output of one [feed] call. */
    data class Scores(val perModel: Map<String, Float>) {
        /** Highest score across all models — what the threshold check uses. */
        val max: Float get() = perModel.values.maxOrNull() ?: 0f
        /** Name of the model that produced [max] (deterministic on ties). */
        val winner: String
            get() = perModel.maxByOrNull { it.value }?.key ?: "?"
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

    private fun runWakeword(model: WakewordModel): Float {
        val flat = FloatArray(model.window * EMBEDDING_DIM)
        val start = embeddingBuffer.size - model.window
        for (i in 0 until model.window) {
            val emb = embeddingBuffer[start + i]
            System.arraycopy(emb, 0, flat, i * EMBEDDING_DIM, EMBEDDING_DIM)
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            longArrayOf(1L, model.window.toLong(), EMBEDDING_DIM.toLong()),
        )
        return tensor.use {
            model.session.run(mapOf(model.inputName to it)).use { result ->
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
        for (model in models) {
            runCatching { model.session.close() }
        }
        runCatching { embeddingSession.close() }
        runCatching { melSession.close() }
        // Don't close OrtEnvironment — it's a process-wide singleton
        // shared with SpeechActivityDetector.
        KLog.i(TAG, "WakeWordDetector closed")
    }

    private companion object {
        const val TAG = "WakeWordDetector"
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMBEDDING_HOP = 8
        const val EMBEDDING_DIM = 96
        const val MEL_BUFFER_MAX = 200
        // A few embeddings of headroom on top of the deepest model's
        // own window — keeps memory bounded while still surviving a
        // small burst of mel frames produced from a single feed() call.
        const val EMBEDDING_BUFFER_HEADROOM = 8

        /**
         * The classifier heads we load. Add another (name, asset path)
         * tuple to enable a new wake phrase — no other changes needed.
         */
        val WAKEWORD_MODELS = listOf(
            "kiwi" to "wakeword/kiwi_variants.onnx",
            "alexa" to "wakeword/alexa_v0.1.onnx",
        )
    }
}
