package com.kiwi.assistant.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.kiwi.assistant.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Comprueba periódicamente el endpoint /api/version del backend, descarga
 * el APK más reciente cuando hay versión nueva, y lo instala silenciosamente
 * usando los privilegios de Device Owner.
 *
 * `canInstall` actúa como compuerta: cada paso disruptivo (descarga e
 * instalación) la consulta y aborta si devuelve false. Sin esta compuerta
 * la instalación ocurre cuando llega, lo que en una sesión activa de Kiwi
 * mata la app a mitad de una respuesta de Gemini.
 *
 * En desarrollo (sin Device Owner) la instalación silenciosa falla y el
 * sistema muestra el diálogo de instalación normal — útil para probar el
 * pipeline sin haber configurado la tablet como dispositivo dedicado.
 */
class AutoUpdater(
    private val context: Context,
    private val canInstall: () -> Boolean = { true },
) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** Single-shot check. Returns true if an install was attempted. */
    suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.CLOUD_RUN_URL.isEmpty() || BuildConfig.KIWI_API_KEY.isEmpty()) {
            return@withContext false
        }
        if (!canInstall()) {
            Log.i(TAG, "skipping update check: app is in an active session")
            return@withContext false
        }

        val info = fetchVersionInfo() ?: return@withContext false
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            Log.i(
                TAG,
                "up to date (local=${BuildConfig.VERSION_CODE}, remote=${info.versionCode})",
            )
            return@withContext false
        }
        if (info.apkUrl.isBlank()) {
            Log.w(TAG, "remote version ${info.versionCode} has no apk_url")
            return@withContext false
        }

        Log.i(TAG, "update available: ${info.versionName} (${info.versionCode})")
        val apkFile = downloadApk(info.apkUrl, info.versionCode) ?: return@withContext false

        // Re-check the gate: the user may have started a session while the
        // APK was downloading. Defer the install to the next polling cycle.
        if (!canInstall()) {
            Log.i(TAG, "session started during download; deferring install")
            return@withContext false
        }
        runCatching { installSilently(apkFile) }
            .onFailure { Log.w(TAG, "install failed", it) }
        true
    }

    /**
     * Polls forever at `intervalMillis` until the surrounding coroutine
     * scope is cancelled. Errors during a single iteration are logged and
     * swallowed so a transient network failure doesn't kill the loop.
     */
    suspend fun runForever(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            runCatching { runOnce() }
                .onFailure { Log.w(TAG, "update check failed", it) }
            delay(intervalMillis)
        }
    }

    private fun fetchVersionInfo(): VersionInfo? {
        val url = BuildConfig.CLOUD_RUN_URL.trimEnd('/') + "/api/version"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", BuildConfig.KIWI_API_KEY)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "version check returned ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                json.decodeFromString(VersionInfo.serializer(), body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "version fetch failed", e)
            null
        }
    }

    private fun downloadApk(apkUrl: String, versionCode: Int): File? {
        val request = Request.Builder().url(apkUrl).build()
        val target = File(context.cacheDir, "kiwi-v$versionCode.apk")
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "download returned ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            null
        }
    }

    private fun installSilently(apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("kiwi.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install committed for session $sessionId")
    }

    private companion object {
        const val TAG = "AutoUpdater"
        const val DEFAULT_INTERVAL_MILLIS = 30L * 60L * 1_000L // 30 min
    }
}
