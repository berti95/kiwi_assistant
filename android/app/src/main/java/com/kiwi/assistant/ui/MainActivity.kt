package com.kiwi.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
        viewModel.setMicrophonePermission(hasMicPermission())
        // Belt-and-braces: setMicrophonePermission already starts the
        // wake-word listener when permission flips from false to true,
        // but if the app was force-stopped or the listener crashed,
        // calling ensureWakeWordListening() on every resume guarantees
        // that returning to the clock leaves the mic open for "hey
        // jarvis".
        viewModel.ensureWakeWordListening()
        startUpdater()
    }

    override fun onPause() {
        brightnessManager.stop()
        viewModel.releaseMicForBackground()
        stopUpdater()
        super.onPause()
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
