package com.kiwi.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.kiwi.assistant.log.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Reemplazo de [WakeWordListener] basado en Vosk (ASR offline
 * español) en vez de openWakeWord (classifier sobre embedding
 * inglés).
 *
 * Razones del cambio: el embedding de openWakeWord es Google Speech
 * embedding, entrenado mayormente con inglés. Cualquier wake word
 * español sobre ese embedding tenía un techo bajo y nuestros logs
 * mostraban un modelo ``kiwi_variants.onnx`` con peak 0.001
 * constante (no detectaba nada en producción).
 *
 * Pipeline:
 *  1. [VoskModelManager.ensure] descarga (1ª vez) y carga el modelo
 *     español ~40 MB.
 *  2. Construimos un [Recognizer] con grammar restringido a las
 *     frases de activación + "[unk]" — esto fuerza a Vosk a producir
 *     SOLO esas frases (o "[unk]") en lugar de transcribir libremente,
 *     mucho más rápido y más preciso para nuestro caso.
 *  3. Loop AudioRecord → recognizer.acceptWaveForm(). Al detectar
 *     una de las frases en el resultado parcial / final, disparamos.
 *
 * Compatibilidad: mismo interface público (``start(scope,
 * onDetected) → Boolean`` + ``stop()``) que el anterior, así el
 * ViewModel no necesita conocer el cambio interno.
 */
class VoskKeywordListener(
    context: Context,
    private val phrases: List<String> = DEFAULT_PHRASES,
) {

    private val appContext = context.applicationContext
    private val modelManager = VoskModelManager(appContext)

    private var job: Job? = null
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private val activeEffects = mutableListOf<AudioEffect>()

    @Volatile private var stopRequested = false

    /**
     * Inicia el listener. Devuelve true (siempre, salvo que ya
     * estuviera corriendo): la carga del modelo / verificación de
     * micro pueden tardar, así que es asíncrono. Si fallan, el
     * listener termina silenciosamente y el usuario puede iniciar
     * conversación con el botón "Habla con Kiwi".
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onDetected: () -> Unit): Boolean {
        if (job?.isActive == true) return true
        stopRequested = false

        job = scope.launch(Dispatchers.IO) {
            val model = modelManager.ensure()
            if (model == null) {
                KLog.w(TAG, "Vosk model unavailable; wake-word disabled")
                return@launch
            }

            // Grammar JSON con frases de activación. "[unk]" es la
            // catch-all de Vosk para "audio no reconocido" — sin
            // ella el recognizer fuerza la salida a una de las
            // phrases incluso cuando dices algo que no toca.
            val grammar = (phrases + UNK).joinToString(
                prefix = "[",
                separator = ",",
                postfix = "]",
            ) { "\"$it\"" }

            val rec = try {
                Recognizer(model, SAMPLE_RATE_HZ.toFloat(), grammar)
            } catch (e: Exception) {
                KLog.e(TAG, "Recognizer init failed", e)
                return@launch
            }
            recognizer = rec

            val record = openAudioRecord() ?: run {
                runCatching { rec.close() }
                recognizer = null
                return@launch
            }
            recorder = record
            applyAudioEffects(record)
            record.startRecording()
            KLog.i(
                TAG,
                "Vosk wake-word listener started (phrases=$phrases)",
            )

            try {
                val buffer = ShortArray(CHUNK_SAMPLES)
                while (
                    !stopRequested &&
                    record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0 || stopRequested) continue
                    val accepted = rec.acceptWaveForm(buffer, read)
                    val recognised = if (accepted) {
                        extractText(rec.result, "text")
                    } else {
                        extractText(rec.partialResult, "partial")
                    }
                    if (recognised.isBlank()) continue
                    val matched = matchPhrase(recognised)
                    if (matched != null) {
                        KLog.i(
                            TAG,
                            "Wake word DETECTED (phrase='$matched', " +
                                "heard='$recognised')",
                        )
                        // Liberar mic + recognizer ANTES de notificar
                        // para que el capture de la sesión pueda
                        // agarrar el AudioRecord sin carrera.
                        releaseResources()
                        withContext(Dispatchers.Main) {
                            if (!stopRequested) onDetected()
                        }
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.e(TAG, "Vosk loop crashed", e)
            } finally {
                releaseResources()
                KLog.i(TAG, "Vosk loop exiting")
            }
        }
        return true
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        releaseResources()
    }

    // ---- helpers ------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            KLog.w(TAG, "Invalid min buffer size: $minBuffer")
            return null
        }
        val bufferSize = (minBuffer * 4).coerceAtLeast(CHUNK_SAMPLES * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            KLog.w(TAG, "AudioRecord failed to initialize (permission missing?).")
            record.release()
            return null
        }
        return record
    }

    private fun applyAudioEffects(record: AudioRecord) {
        // Igual que en el listener anterior: NoiseSuppressor + AGC
        // si el OEM los expone. Vosk también se beneficia (limpia
        // ruido de fondo, sube señal débil) antes de ASR.
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId) }
                .getOrNull()?.let { fx ->
                    fx.enabled = true
                    activeEffects.add(fx)
                    KLog.i(TAG, "NoiseSuppressor enabled")
                }
        }
        if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(record.audioSessionId) }
                .getOrNull()?.let { fx ->
                    fx.enabled = true
                    activeEffects.add(fx)
                    KLog.i(TAG, "AutomaticGainControl enabled")
                }
        }
    }

    @Synchronized
    private fun releaseResources() {
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
        for (fx in activeEffects) {
            runCatching { fx.enabled = false }
            runCatching { fx.release() }
        }
        activeEffects.clear()
    }

    private fun matchPhrase(heard: String): String? {
        val normalised = heard.lowercase().trim()
        if (normalised.isEmpty() || normalised == UNK) return null
        // Substring match — robusto a recognitions parciales tipo
        // "hola kiwi" vs "hola kiwi pon". Iteramos en el orden dado
        // así una phrase más específica ("hola kiwi") gana sobre la
        // genérica ("kiwi") si ambas matchean.
        return phrases.firstOrNull { phrase ->
            phrase.lowercase() in normalised
        }
    }

    private fun extractText(json: String, key: String): String {
        if (json.isBlank()) return ""
        return try {
            JSONObject(json).optString(key, "")
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val TAG = "VoskKeywordListener"
        const val SAMPLE_RATE_HZ = 16_000
        // 80 ms a 16 kHz. Vosk acepta chunks de cualquier tamaño;
        // mantenemos el mismo de antes para que la lectura del mic
        // sea predecible.
        const val CHUNK_SAMPLES = 1_280
        const val UNK = "[unk]"

        /**
         * Lista de frases que Vosk va a escuchar. Subset cerrado
         * para que el recognizer use grammar restringido (más rápido
         * + más preciso). Añadir / quitar entradas aquí sin
         * reentrenar nada.
         *
         * Sólo formas con verbo de invocación — sin "kiwi" / "alexa"
         * sueltas. Vosk con grammar restringido mapea cualquier
         * sonido reconocible a una de las phrases o a "[unk]";
         * dejar las palabras solas hacía que casi cualquier ruido
         * con sílabas parecidas las activase. Forzar un prefijo
         * ("hola", "oye", "hey") obliga al recognizer a acertar
         * acústica de toda la frase antes de disparar.
         */
        val DEFAULT_PHRASES = listOf(
            "hola kiwi",
            "oye kiwi",
            "hey kiwi",
            "hola alexa",
            "hey alexa",
        )
    }
}
