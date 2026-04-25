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
import com.kiwi.assistant.kiosk.KioskController
import com.kiwi.assistant.ui.theme.KiwiTheme
import com.kiwi.assistant.util.BrightnessManager

class MainActivity : ComponentActivity() {

    private lateinit var kioskController: KioskController
    private lateinit var brightnessManager: BrightnessManager
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
    }

    override fun onPause() {
        brightnessManager.stop()
        super.onPause()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
