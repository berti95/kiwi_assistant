package com.kiwi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen(userTranscript: String = "") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 64.dp),
    ) {
        if (userTranscript.isNotBlank()) {
            // Echo what Gemini heard so the user can see if they were
            // understood while the response is still being generated.
            Text(
                text = "Tú: $userTranscript",
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }

        CircularProgressIndicator(
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.Center),
            color = Color(0xFF7DDB6A),
            strokeWidth = 4.dp,
        )
    }
}
