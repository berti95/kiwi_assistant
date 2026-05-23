package com.kiwi.assistant.audio

import android.content.Context
import com.kiwi.assistant.log.KLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model

/**
 * Asegura que el modelo Vosk español esté disponible en
 * almacenamiento interno y devuelve una instancia [Model] lista para
 * usar.
 *
 * En el primer arranque descarga el ZIP desde alphacephei.com (~40
 * MB) y lo extrae a ``filesDir/vosk-model-es/``. Una vez extraído,
 * un fichero ``.ready`` marca que el modelo está completo; arranques
 * posteriores cargan directamente sin red.
 *
 * Decisión de no empaquetar el modelo en el APK: ~40 MB extra por
 * encima de la AAR de Vosk ya disponibles harían el APK pesar
 * mucho. Como la tablet vive enchufada y conectada, descargar una
 * vez al instalar es preferible a inflar cada release.
 *
 * Resiliencia: si la descarga falla, devolvemos null y el listener
 * que nos llamó simplemente no arma el wake word — el usuario puede
 * abrir la conversación con el botón "Habla con Kiwi". Al siguiente
 * arranque volvemos a intentar.
 */
class VoskModelManager(context: Context) {

    private val modelDir = File(context.filesDir, MODEL_DIR_NAME)
    private val readyFlag = File(modelDir, READY_FLAG_NAME)
    private val cacheZip = File(context.cacheDir, ZIP_NAME)

    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    @Volatile private var cached: Model? = null

    /**
     * Devuelve el modelo cargado (o lo prepara la primera vez).
     * Null si la descarga/extracción/carga falla. La llamada es
     * idempotente: una vez instalado, el coste es sólo abrir [Model].
     */
    suspend fun ensure(): Model? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                if (!readyFlag.exists()) {
                    installModel()
                }
                Model(modelDir.absolutePath).also { cached = it }
            } catch (e: Exception) {
                KLog.e(TAG, "Vosk model setup failed", e)
                null
            }
        }
    }

    fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
    }

    private fun installModel() {
        KLog.i(TAG, "downloading Vosk model from $MODEL_URL")
        cacheZip.parentFile?.mkdirs()
        val request = Request.Builder().url(MODEL_URL).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("download HTTP ${response.code}")
            }
            val body = response.body
                ?: throw RuntimeException("download empty body")
            cacheZip.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
        }
        KLog.i(TAG, "downloaded ${cacheZip.length() / 1024} KB; extracting")

        // El ZIP de Vosk contiene un directorio top-level del estilo
        // "vosk-model-small-es-0.42/" — strippeamos esa primera parte
        // para que el modelo quede directamente bajo modelDir.
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()
        ZipInputStream(cacheZip.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val relPath = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relPath.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val target = File(modelDir, relPath)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
        cacheZip.delete()
        readyFlag.createNewFile()
        KLog.i(TAG, "model ready at ${modelDir.absolutePath}")
    }

    private companion object {
        const val TAG = "VoskModelManager"
        // Versión pequeña del modelo español — ~40 MB descargado,
        // ~120 MB extraído. Suficiente para keyword spotting / wake
        // word. Si Vosk publica una versión posterior se puede subir
        // tocando esta URL.
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        const val MODEL_DIR_NAME = "vosk-model-es"
        const val READY_FLAG_NAME = ".ready"
        const val ZIP_NAME = "vosk-model-download.zip"
    }
}
