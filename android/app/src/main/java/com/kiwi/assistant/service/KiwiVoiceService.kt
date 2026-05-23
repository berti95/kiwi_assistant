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
import com.kiwi.assistant.a11y.KiwiAccessibilityService
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

    // Escena visual producida por la respuesta actual (calendario,
    // lista de vídeos…). Si la hay, al cerrar la conversación traemos
    // Kiwi al frente con ella en vez de re-armar el wake word de fondo.
    private var pendingForegroundScene: Scene? = null

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

    /**
     * Tras cerrar (por el usuario o por el motor): limpiar. Si la
     * respuesta produjo una escena visual, traemos Kiwi al frente con
     * ella (MainActivity parará este service y armará su propio wake
     * word). Si fue una respuesta de voz pura, seguimos escuchando
     * "oye kiwi" en background.
     */
    private fun afterClose() {
        stateJob?.cancel()
        stateJob = null
        overlay.hide()
        val foreground = pendingForegroundScene
        pendingForegroundScene = null
        if (foreground != null) {
            bringKiwiToForeground(foreground)
            return
        }
        startWakeWord()
    }

    /**
     * Trae Kiwi (MainActivity) al frente mostrando [scene]. Deja la
     * escena en [ForegroundSceneRelay] para que MainActivity la pinte
     * al reanudar. Si por algo no podemos lanzar la Activity, caemos
     * a re-armar el wake word para no quedarnos sordos.
     */
    private fun bringKiwiToForeground(scene: Scene) {
        ForegroundSceneRelay.put(scene)
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        runCatching { intent?.let { startActivity(it) } }
            .onFailure {
                KLog.w(TAG, "no se pudo traer Kiwi al frente: ${it.message}")
                ForegroundSceneRelay.consume()  // descarta la escena huérfana
                startWakeWord()
            }
    }

    /**
     * Escenas que merece la pena ver en grande en la app (no solo como
     * texto en la pill): listas, calendario, vídeos, lo que suena. Las
     * pasivas (timer, reproducción de vídeo) o de sistema (alarma
     * sonando) NO traen Kiwi al frente — interrumpirían lo que estás
     * viendo sin aportar.
     */
    private fun shouldShowInForeground(scene: Scene): Boolean = when (scene) {
        is Scene.Calendar,
        is Scene.VideoList,
        is Scene.PlaylistList,
        is Scene.TodoList,
        is Scene.AlarmList,
        is Scene.ShoppingList,
        is Scene.UsageStats,
        is Scene.NowPlaying,
        -> true
        else -> false
    }

    // ---- ConversationEngine.Host ------------------------------------

    override fun onSceneSet(scene: Scene) {
        // El overlay solo muestra el transcript en la pill. Si la
        // respuesta produce una escena visual (calendario, listas,
        // vídeos…), la recordamos: al terminar de hablar traeremos
        // Kiwi al frente con ella en vez de dejarla escondida. Las
        // device_command (reproducir, etc.) se manejan abajo.
        if (shouldShowInForeground(scene)) {
            pendingForegroundScene = scene
        }
    }

    override fun onDeviceCommand(event: KiwiSessionEvent.DeviceCommand) {
        when (event.command) {
            "open_app_url" -> {
                event.url?.let { openUrlInApp(it, event.packageName) }
                // El usuario se va a la app (YouTube) → no traer Kiwi
                // al frente aunque la búsqueda dejara una escena.
                pendingForegroundScene = null
                engine.close()
                afterClose()
            }
            "open_app_then_return" -> {
                event.packageName?.let { launchApp(it) }
            }
            "set_volume" -> applyVolume(event.level, event.delta)
            "ui_click" -> clickByLabel(event.label)
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

    /**
     * Pulsa por voz un botón de la app en primer plano (YouTube)
     * buscándolo por su etiqueta — delega en el AccessibilityService.
     * El overlay no roba el foco, así que la ventana activa es la app
     * de debajo. Si el servicio no está habilitado (instance == null)
     * lo dejamos en un warning; el usuario lo activa en Ajustes →
     * Accesibilidad → Kiwi.
     */
    private fun clickByLabel(label: String?) {
        if (label.isNullOrBlank()) {
            KLog.w(TAG, "ui_click sin etiqueta")
            return
        }
        val svc = KiwiAccessibilityService.instance
        if (svc == null) {
            KLog.w(TAG, "ui_click: AccessibilityService no habilitado")
            return
        }
        KLog.i(TAG, "ui_click('$label') → ${svc.clickByLabel(label)}")
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
