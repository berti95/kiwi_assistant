package com.kiwi.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.kiwi.assistant.audio.VoskKeywordListener
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service que mantiene el wake word vivo cuando la app de
 * Kiwi NO está en primer plano (p.ej. el usuario está viendo un vídeo
 * en YouTube). Sin esto, el micro se libera al backgroundear la
 * Activity y "oye kiwi" deja de funcionar fuera de Kiwi.
 *
 * Fase 2a: al detectar el wake word, trae [MainActivity] al frente
 * con un extra para que el ViewModel abra sesión de inmediato — el
 * "salto" a primer plano. La conversación sigue ocurriendo en la
 * Activity/ViewModel como siempre, así reutilizamos todo el pipeline
 * ya probado. (Fase 2b cambiará esta acción por un overlay encima de
 * la otra app, sin saltar.)
 *
 * Disciplina de micro (un solo AudioRecord a la vez):
 *  - Activity en foreground → el wake word lo lleva el ViewModel.
 *  - Activity en background → lo lleva ESTE service.
 * La Activity arranca el service en onPause y lo para en onResume, así
 * nunca compiten por el micro.
 */
class KiwiVoiceService : Service() {

    private val listener by lazy { VoskKeywordListener(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        // START_STICKY: si el SO mata el service por presión de memoria,
        // que lo relance — queremos el wake word siempre disponible
        // mientras estemos en background.
        return START_STICKY
    }

    private fun startListening() {
        val ok = listener.start(scope) {
            KLog.i(TAG, "background wake word → trayendo Kiwi al frente")
            launchKiwi()
            // El listener ya liberó el micro (releaseResources) antes de
            // este callback; paramos el service para que la Activity
            // tome el micro sin carrera.
            stopSelf()
        }
        if (!ok) KLog.w(TAG, "no se pudo arrancar el wake word en el service")
    }

    private fun launchKiwi() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra(EXTRA_WAKE, true)
        }
        runCatching { startActivity(intent) }.onFailure {
            // Sin SYSTEM_ALERT_WINDOW el SO puede bloquear el background
            // activity start. Lo registramos; el usuario concede el
            // permiso desde la Activity.
            KLog.w(TAG, "startActivity desde service falló: ${it.message}")
        }
    }

    override fun onDestroy() {
        listener.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kiwi escuchando",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantiene a Kiwi atento a 'oye kiwi' en segundo plano."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kiwi está escuchando")
            .setContentText("Di 'oye kiwi' desde cualquier app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KiwiVoiceService"
        private const val CHANNEL_ID = "kiwi_voice"
        private const val NOTIF_ID = 42

        /** Extra que la Activity lee para abrir sesión tras un wake. */
        const val EXTRA_WAKE = "com.kiwi.assistant.WAKE"

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
