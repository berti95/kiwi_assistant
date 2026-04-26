package com.kiwi.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.kiwi.assistant.ui.screens.ErrorScreen
import com.kiwi.assistant.ui.screens.IdleScreen
import com.kiwi.assistant.ui.screens.ListeningScreen
import com.kiwi.assistant.ui.screens.ProcessingScreen
import com.kiwi.assistant.ui.screens.RespondingScreen
import com.kiwi.assistant.ui.screens.StandbyScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KiwiScreen(viewModel: KiwiViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }

    // Single tap drives the state machine; long-press anywhere ends the
    // whole conversation and returns to the clock.
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
            is KiwiState.Standby -> StandbyScreen()
            is KiwiState.Listening -> ListeningScreen()
            is KiwiState.Processing -> ProcessingScreen()
            is KiwiState.Responding -> RespondingScreen(transcript = s.transcript)
            is KiwiState.Error -> ErrorScreen(message = s.message)
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
