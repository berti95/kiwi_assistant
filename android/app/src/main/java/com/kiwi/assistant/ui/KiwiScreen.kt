package com.kiwi.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiwi.assistant.ui.screens.ErrorScreen
import com.kiwi.assistant.ui.screens.IdleScreen
import com.kiwi.assistant.ui.screens.ListeningScreen
import com.kiwi.assistant.ui.screens.ProcessingScreen
import com.kiwi.assistant.ui.screens.RespondingScreen

@Composable
fun KiwiScreen(viewModel: KiwiViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        is KiwiState.Idle -> IdleScreen()
        is KiwiState.Listening -> ListeningScreen()
        is KiwiState.Processing -> ProcessingScreen()
        is KiwiState.Responding -> RespondingScreen(transcript = s.transcript)
        is KiwiState.Error -> ErrorScreen(message = s.message)
    }
}
