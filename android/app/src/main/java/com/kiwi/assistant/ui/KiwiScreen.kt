package com.kiwi.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiwi.assistant.BuildConfig
import com.kiwi.assistant.ui.screens.ConnectingScreen
import com.kiwi.assistant.ui.screens.ErrorScreen
import com.kiwi.assistant.ui.screens.IdleScreen
import com.kiwi.assistant.ui.screens.ListeningScreen
import com.kiwi.assistant.ui.screens.ProcessingScreen
import com.kiwi.assistant.ui.screens.RespondingScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KiwiScreen(viewModel: KiwiViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }

    // Single tap drives the state machine; long-press anywhere ends the
    // whole conversation. The visible close button (top-left, only
    // shown while a session is active) is the discoverable equivalent
    // of the long-press, for users who don't know about the gesture.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = viewModel::onTap,
                onLongClick = viewModel::onLongPress,
            ),
    ) {
        when (val s = state) {
            is KiwiState.Idle -> IdleScreen()
            is KiwiState.Connecting -> ConnectingScreen()
            is KiwiState.Listening -> ListeningScreen()
            is KiwiState.Processing -> ProcessingScreen(userTranscript = s.userTranscript)
            is KiwiState.Responding -> RespondingScreen(
                userTranscript = s.userTranscript,
                kiwiTranscript = s.kiwiTranscript,
            )
            is KiwiState.Error -> ErrorScreen(message = s.message)
        }

        // Close button: only meaningful while a session is active. Hidden
        // in Idle (clock — already "closed") and Error (a tap there
        // already returns to Idle, no separate close needed).
        val sessionActive = state !is KiwiState.Idle && state !is KiwiState.Error
        if (sessionActive) {
            IconButton(
                onClick = viewModel::onEndSession,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar conversación",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Discreet version badge so we can tell at a glance which release
        // is running on the tablet — useful while iterating on the auto-
        // updater. Bottom-right corner, low contrast so it doesn't fight
        // the clock for attention.
        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = Color.White.copy(alpha = 0.25f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        )
    }
}
