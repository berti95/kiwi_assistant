package com.kiwi.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.audio.VoskKeywordListener
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.network.KiwiSessionEvent
import com.kiwi.assistant.ui.PipelineState
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.voice.ConversationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service que mantiene el wake word vivo cuando la app de
 * Kiwi NO está en primer plano, y —Fase 2b-2— conversa con un overlay
 * ENCIMA de la app actual (YouTube, etc.) en vez de traer Kiwi al
 * frente.
 *
 * Flujo:
 *  1. Escucha "oye kiwi" con un VoskKeywordListener propio.
 *  2. Al detectar: libera el wake word (mic libre), muestra el overlay
 *     y abre un [ConversationEngine] propio. La conversación corre
 *     aquí, en background, mientras YouTube sigue delante.
 *  3. El foco de audio (que pide el motor) pausa YouTube mientras
 *     hablas y lo reanuda al cerrar.
 *  4. Al cerrar (respuesta dada, no-speech, o tap en el overlay):
 *     oculta el overlay y re-arma el wake word.
 *
 * Disciplina de micro: wake word y captura del motor nunca a la vez —
 * el listener libera el AudioRecord en su callback antes de que
 * abramos el motor; al cerrar, el motor suelta y re-armamos el wake
 * word.
 */
class KiwiVoiceService : Service(), ConversationEngine.Host {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener by lazy { VoskKeywordListener(applicationContext) }
    private val engine by lazy {
        ConversationEngine(
            context = applicationContext,
            scope = scope,
            cloudRunUrl = BuildConfig.CLOUD_RUN_URL,
            apiKey = BuildConfig.KIWI_API_KEY,
            host = this,
        )
    }
    private val overlay by lazy { ConversationOverlay(applicationContext) }
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWakeWord()
        return START_STICKY
    }

    private fun startWakeWord() {
        val ok = listener.start(scope) {
            KLog.i(TAG, "background wake word → conversación en overlay")
            onWake()
        }
        if (!ok) KLog.w(TAG, "no se pudo arrancar el wake word en el service")
    }

    /** Wake detectado: el listener ya liberó el micro. Monta overlay + motor. */
    private fun onWake() {
        overlay.show(onTap = { closeFromUser() })
        stateJob?.cancel()
        stateJob = scope.launch {
            engine.pipeline.collect { state -> overlay.render(state) }
        }
        engine.open(micPermissionGranted = hasMicPermission())
    }

    private fun closeFromUser() {
        engine.close()
        afterClose()
    }

    /** Tras cerrar (por el usuario o por el motor): limpiar y re-armar. */
    private fun afterClose() {
        stateJob?.cancel()
        stateJob = null
        overlay.hide()
        // Re-armar el wake word para seguir escuchando "oye kiwi".
        startWakeWord()
    }

    // ---- ConversationEngine.Host ------------------------------------

    override fun onSceneSet(scene: Scene) {
        // El overlay no pinta escenas (calendar, listas…): Kiwi
        // responde por voz y el overlay muestra el transcript. Las
        // device_command (reproducir, etc.) sí se manejan abajo.
    }

    override fun onDeviceCommand(event: KiwiSessionEvent.DeviceCommand) {
        when (event.command) {
            "open_app_url" -> {
                event.url?.let { openUrlInApp(it, event.packageName) }
                // El usuario se va a la app → cerrar conversación.
                engine.close()
                afterClose()
            }
            "open_app_then_return" -> {
                event.packageName?.let { launchApp(it) }
            }
            "set_volume" -> applyVolume(event.level, event.delta)
            else -> KLog.w(TAG, "unknown device_command: ${event.command}")
        }
    }

    /** En el overlay cerramos tras cada respuesta — UX corta sobre otra
     *  app. Para seguir, el usuario dice "oye kiwi" otra vez. */
    override fun shouldCloseAfterResponse(): Boolean = true

    override fun onClosed() {
        afterClose()
    }

    // ---- device command helpers (overlay-side) ----------------------

    private fun openUrlInApp(url: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
        }
        val ok = runCatching { startActivity(intent) }.isSuccess
        if (!ok && packageName != null) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun launchApp(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    private fun applyVolume(level: Int?, delta: Int?) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val targetSteps = when {
            level != null -> ((level.coerceIn(0, 100) * max) + 50) / 100
            delta != null -> {
                val currentPct = (current * 100 + max / 2) / max
                ((( currentPct + delta).coerceIn(0, 100) * max) + 50) / 100
            }
            else -> return
        }
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetSteps, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        stateJob?.cancel()
        overlay.hide()
        listener.stop()
        engine.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Kiwi escuchando",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene a Kiwi atento a 'oye kiwi' en segundo plano."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kiwi está escuchando")
            .setContentText("Di 'oye kiwi' desde cualquier app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "KiwiVoiceService"
        private const val CHANNEL_ID = "kiwi_voice"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KiwiVoiceService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KiwiVoiceService::class.java))
        }
    }
}
