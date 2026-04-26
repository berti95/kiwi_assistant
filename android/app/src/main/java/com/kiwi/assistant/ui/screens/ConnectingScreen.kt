package com.kiwi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Brief transitional state shown while the WebSocket finishes opening
 * after the user's first tap. Auto-advances to ListeningScreen as soon
 * as the server sends session.ready, so this is usually a sub-second
 * blip — just enough that the UI doesn't feel frozen.
 */
@Composable
fun ConnectingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = Color.White.copy(alpha = 0.4f),
            strokeWidth = 3.dp,
        )
    }
}
