package com.kiwi.assistant.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kiwi.assistant.kiosk.KioskController
import com.kiwi.assistant.ui.theme.KiwiTheme
import com.kiwi.assistant.util.BrightnessManager

class MainActivity : ComponentActivity() {

    private lateinit var kioskController: KioskController
    private lateinit var brightnessManager: BrightnessManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kioskController = KioskController(this)
        brightnessManager = BrightnessManager(this)
        setContent {
            KiwiTheme {
                KiwiScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        kioskController.enableKiosk()
        brightnessManager.start()
    }

    override fun onPause() {
        brightnessManager.stop()
        super.onPause()
    }
}
