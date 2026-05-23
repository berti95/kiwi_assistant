package com.kiwi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shown after a transient WebSocket failure (typical post-Doze
 * "Software caused connection abort") while the ViewModel runs an
 * automatic retry pass. Friendlier than the bare ErrorScreen because
 * the user doesn't have to do anything — they just see "Sin conexión,
 * reintentando…" and the screen recovers on its own.
 */
@Composable
fun ReconnectingScreen(attempt: Int, maxAttempts: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = Color.White.copy(alpha = 0.4f),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Sin conexión, reintentando…",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
            )
            if (maxAttempts > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Intento $attempt de $maxAttempts",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
