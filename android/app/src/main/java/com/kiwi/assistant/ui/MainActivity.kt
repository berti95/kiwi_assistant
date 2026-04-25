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
import androidx.lifecycle.lifecycleScope
import com.kiwi.assistant.kiosk.KioskController
import com.kiwi.assistant.ui.theme.KiwiTheme
import com.kiwi.assistant.updater.AutoUpdater
import com.kiwi.assistant.util.BrightnessManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var kioskController: KioskController
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var autoUpdater: AutoUpdater
    private var updaterJob: Job? = null
    private val viewModel: KiwiViewModel by viewModels()

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setMicrophonePermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kioskController = KioskController(this)
        brightnessManager = BrightnessManager(this)
        autoUpdater = AutoUpdater(applicationContext)

        viewModel.setMicrophonePermission(hasMicPermission())
        if (!hasMicPermission()) requestMic.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            KiwiTheme {
                KiwiScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        kioskController.enableKiosk()
        brightnessManager.start()
        viewModel.setMicrophonePermission(hasMicPermission())
        startUpdater()
    }

    override fun onPause() {
        brightnessManager.stop()
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

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
