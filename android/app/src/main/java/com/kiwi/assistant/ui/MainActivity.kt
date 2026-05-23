package com.kiwi.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.alarm.AlarmRingReceiver
import com.kiwi.assistant.log.LogShipper
import com.kiwi.assistant.service.KiwiVoiceService
import com.kiwi.assistant.ui.theme.KiwiTheme
import com.kiwi.assistant.updater.AutoUpdater
import com.kiwi.assistant.util.BrightnessManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var brightnessManager: BrightnessManager
    private lateinit var autoUpdater: AutoUpdater
    private var updaterJob: Job? = null
    private val viewModel: KiwiViewModel by viewModels()
    private val logShipper = LogShipper(
        baseUrl = BuildConfig.CLOUD_RUN_URL,
        apiKey = BuildConfig.KIWI_API_KEY,
    )

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setMicrophonePermission(granted)
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* la notificación del FGS se mostrará si se concede; no-op si no */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        brightnessManager = BrightnessManager(this)
        // Updater only allowed to install when the user is not in the
        // middle of a Kiwi session. Otherwise Android force-stops the app
        // mid-response to apply the new APK.
        autoUpdater = AutoUpdater(applicationContext) {
            viewModel.pipeline.value is PipelineState.Idle
        }

        viewModel.setMicrophonePermission(hasMicPermission())
        if (!hasMicPermission()) requestMic.launch(Manifest.permission.RECORD_AUDIO)

        // Permisos para el wake word en background (Fase 2):
        //  - POST_NOTIFICATIONS (Android 13+): para que se vea la
        //    notificación obligatoria del foreground service.
        //  - "Mostrar sobre otras apps": para que el service pueda
        //    traer Kiwi al frente desde background (y el overlay de
        //    Fase 2b). Se concede en Ajustes, así que solo lanzamos
        //    la pantalla si falta.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ensureOverlayPermission()

        // Start shipping app logs to the backend so the developer can
        // see what the tablet is doing without ADB. Outlives the
        // session WebSocket — useful for debugging crashes that prevent
        // a session from opening at all.
        logShipper.start(lifecycleScope)

        setContent {
            KiwiTheme {
                KiwiScreen(viewModel = viewModel)
            }
        }

        handleAlarmIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTask sends new alarm ring intents through
        // here when MainActivity is already running. Without this we'd
        // miss every alarm except the very first one after launch.
        setIntent(intent)
        handleAlarmIntent(intent)
    }

    private fun handleAlarmIntent(intent: Intent) {
        val id = intent.getStringExtra(AlarmRingReceiver.EXTRA_ID) ?: return
        val label = intent.getStringExtra(AlarmRingReceiver.EXTRA_LABEL).orEmpty()
        val firesAt = intent.getLongExtra(AlarmRingReceiver.EXTRA_FIRES_AT, 0L)
        viewModel.onAlarmRing(id, label, firesAt)
        // Strip the extras so a config change (rotation, resume) doesn't
        // re-fire the same alarm scene.
        intent.removeExtra(AlarmRingReceiver.EXTRA_ID)
        intent.removeExtra(AlarmRingReceiver.EXTRA_LABEL)
        intent.removeExtra(AlarmRingReceiver.EXTRA_FIRES_AT)
    }

    override fun onResume() {
        super.onResume()
        brightnessManager.start()
        // Volvemos a primer plano: paramos el wake word del service
        // (background) ANTES de reabrir el del ViewModel, para que no
        // compitan por el micro. El service, cuando detecta "oye kiwi"
        // en background, conversa con su propio overlay encima de la
        // otra app — no trae esta Activity al frente.
        KiwiVoiceService.stop(this)
        viewModel.setMicrophonePermission(hasMicPermission())
        // Belt-and-braces: setMicrophonePermission ya arranca el wake
        // word cuando el permiso pasa a concedido, pero si la app fue
        // force-stopped o el listener petó, llamarlo en cada resume
        // garantiza el micro abierto para "oye kiwi".
        viewModel.ensureWakeWordListening()
        startUpdater()
    }

    override fun onPause() {
        brightnessManager.stop()
        // Soltamos el micro del ViewModel y cedemos el wake word al
        // foreground service, que sigue escuchando "oye kiwi" mientras
        // Kiwi no está en primer plano (p.ej. viendo YouTube).
        viewModel.releaseMicForBackground()
        if (canListenInBackground()) {
            KiwiVoiceService.start(this)
        }
        stopUpdater()
        super.onPause()
    }

    /**
     * El service de wake word en background necesita permiso de micro
     * (obvio) y, para traer la Activity al frente desde background sin
     * que el SO lo bloquee, "Mostrar sobre otras apps"
     * (SYSTEM_ALERT_WINDOW). Si falta alguno, no arrancamos el service
     * — el wake word seguirá funcionando con Kiwi en primer plano.
     */
    private fun canListenInBackground(): Boolean {
        if (!hasMicPermission()) return false
        return Settings.canDrawOverlays(this)
    }

    /**
     * Pide "Mostrar sobre otras apps" si falta. Lo necesita el service
     * para abrir Kiwi desde background (Fase 2a) y para el overlay
     * (Fase 2b). Es un permiso especial que se concede en Ajustes, no
     * con un diálogo runtime normal.
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun startUpdater() {
        if (updaterJob?.isActive == true) return
        updaterJob = lifecycleScope.launch {
            // First check shortly after start so a freshly-installed tablet
            // converges quickly; subsequent checks happen at the configured
            // interval inside runForever().
            autoUpdater.runOnce()
            autoUpdater.runForever()
        }
    }

    private fun stopUpdater() {
        updaterJob?.cancel()
        updaterJob = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Reapply on focus return: the system pulls the bars back in
        // when something interrupts (notification shade, install
        // dialog, permission prompt). Without this the tablet can
        // settle in a state with the status bar permanently visible.
        if (hasFocus) hideSystemBars()
    }

    /**
     * Immersive mode: hide both the status bar and the navigation bar
     * so the app fills the screen edge-to-edge. The user can swipe
     * from any edge to bring them back temporarily, then they fade
     * away again — buen compromiso para que el tablet luzca como un
     * appliance sin bloquear al usuario.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
