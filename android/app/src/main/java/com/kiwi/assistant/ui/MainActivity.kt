package com.kiwi.assistant.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kiwi.assistant.kiosk.KioskController
import com.kiwi.assistant.ui.theme.KiwiTheme

class MainActivity : ComponentActivity() {

    private lateinit var kioskController: KioskController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kioskController = KioskController(this)
        setContent {
            KiwiTheme {
                KiwiScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        kioskController.enableKiosk()
    }
}
