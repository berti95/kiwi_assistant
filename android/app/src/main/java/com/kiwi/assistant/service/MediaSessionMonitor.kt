package com.kiwi.assistant.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kiwi.assistant.log.KLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observador unificado de sesiones de medios del SO.
 *
 * Actúa como [NotificationListenerService] no porque le importen las
 * notificaciones, sino porque ese permiso es la *única* vía pública
 * que Android expone para que una app no-system pueda obtener
 * controllers de MediaSession ajenos. El usuario lo concede una vez
 * en Ajustes → Notificaciones → Acceso → Kiwi.
 *
 * Una vez activo, mantiene un [active] que apunta al MediaController
 * con foco actual (la última app que reproduce). Esto deja al tablet:
 *
 *   - Saber qué suena local-mente sin pegar a la Spotify Web API
 *     (latencia 0 ms, sin coste de cuota).
 *   - Mandar pause/next/previous a esa sesión vía
 *     [dispatchTransportAction] — sirve para YouTube, Pocket Casts,
 *     cualquier reproductor que use MediaSession (95% del ecosistema).
 *
 * No reemplaza al SpotifyStateRepository: ese sigue siendo la
 * autoridad para Spotify Connect (devices remotos). El monitor es la
 * base del MediaController unificado (Fase 8/14 del roadmap).
 */
class MediaSessionMonitor : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        KLog.i(TAG, "MediaSessionMonitor created")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        controllerCallback?.let { cb ->
            registeredController?.unregisterCallback(cb)
        }
        registeredController = null
        controllerCallback = null
        _active.value = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        mgr?.addOnActiveSessionsChangedListener(
            sessionsListener,
            ComponentName(this, MediaSessionMonitor::class.java),
            Handler(Looper.getMainLooper()),
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        mgr?.removeOnActiveSessionsChangedListener(sessionsListener)
        _active.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Cualquier evento de notificación puede correlar con un cambio
        // de media — refrescamos por si una nueva app empezó a reproducir.
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { _ -> refresh() }

    private var registeredController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    private fun refresh() {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return
        val component = ComponentName(this, MediaSessionMonitor::class.java)
        val sessions = runCatching {
            mgr.getActiveSessions(component)
        }.getOrNull().orEmpty()

        // El controller "primario" es el último playing; si ninguno
        // está playing, el primero de la lista (la app más reciente
        // que hizo foco de audio).
        val primary = sessions.firstOrNull { isPlaying(it) }
            ?: sessions.firstOrNull()

        // Reemplazar callback registrado si cambió el controller.
        if (primary?.sessionToken != registeredController?.sessionToken) {
            controllerCallback?.let { cb ->
                registeredController?.unregisterCallback(cb)
            }
            registeredController = primary
            controllerCallback = primary?.let {
                object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        publishCurrent(it, state, it.metadata)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        publishCurrent(it, it.playbackState, metadata)
                    }
                }
            }
            controllerCallback?.let { primary?.registerCallback(it) }
        }
        publishCurrent(primary, primary?.playbackState, primary?.metadata)
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun publishCurrent(
        controller: MediaController?,
        state: PlaybackState?,
        metadata: MediaMetadata?,
    ) {
        if (controller == null) {
            _active.value = null
            return
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        _active.value = ActiveMediaSession(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /**
     * Manda una acción de transporte (play / pause / next / previous)
     * al controller activo. Devuelve true si había controller; false
     * si no había nada a lo que mandarle.
     */
    fun dispatchTransportAction(action: TransportAction): Boolean {
        val controller = registeredController ?: return false
        val ctrl = controller.transportControls ?: return false
        when (action) {
            TransportAction.PLAY -> ctrl.play()
            TransportAction.PAUSE -> ctrl.pause()
            TransportAction.NEXT -> ctrl.skipToNext()
            TransportAction.PREVIOUS -> ctrl.skipToPrevious()
        }
        return true
    }

    enum class TransportAction { PLAY, PAUSE, NEXT, PREVIOUS }

    data class ActiveMediaSession(
        val packageName: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
    )

    companion object {
        private const val TAG = "MediaSessionMonitor"

        @Volatile
        var instance: MediaSessionMonitor? = null
            private set

        private val _active = MutableStateFlow<ActiveMediaSession?>(null)
        val active: StateFlow<ActiveMediaSession?> = _active.asStateFlow()

        /**
         * True si el usuario ha concedido a Kiwi acceso a
         * notificaciones (que es el gating del MediaSessionManager
         * cross-app). Útil para mostrar un onboarding/banner si no
         * está activo.
         */
        fun isEnabled(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(":").any { it.startsWith("$packageName/") }
        }
    }
}
