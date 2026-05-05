package com.kiwi.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.kiwi.assistant.ui.scenes.CalendarScene
import com.kiwi.assistant.ui.scenes.ClockScene
import com.kiwi.assistant.ui.screens.ConnectingScreen
import com.kiwi.assistant.ui.screens.ErrorScreen
import com.kiwi.assistant.ui.screens.ListeningScreen
import com.kiwi.assistant.ui.screens.ProcessingScreen
import com.kiwi.assistant.ui.screens.RespondingScreen

/**
 * Top-level router.
 *
 * The screen is composed of two orthogonal layers:
 *
 *   • **Scene** — the main canvas (clock today; calendar, now-playing,
 *     video player, … coming in later fases). Rendered as the base
 *     layer; persists across pipeline transitions.
 *   • **Pipeline overlay** — Listening / Processing / Responding /
 *     Error. Rendered on top of the scene (or, for [PipelineState.Idle],
 *     not rendered at all so the scene shows through unchanged).
 *
 * Single tap → ViewModel decides what to do given the current pipeline
 * state. Long press → end the conversation. Both gestures are
 * captured at the router level so they fire regardless of which
 * scene/overlay is on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KiwiScreen(viewModel: KiwiViewModel = viewModel()) {
    val pipeline by viewModel.pipeline.collectAsState()
    val scene by viewModel.scene.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = viewModel::onTap,
                onLongClick = viewModel::onLongPress,
            ),
    ) {
        // Base layer: the active scene. Clock for now; future fases
        // add Calendar, NowPlaying, VideoPlayer, BrowseYT.
        SceneLayer(scene)

        // Overlay layer. Two modes:
        //
        // 1. Scene is Idle (the clock) → fullscreen overlay covers
        //    everything. Same UX as before scenes existed.
        // 2. Scene is non-Idle (calendar, now-playing, …) → compact
        //    HUD pinned at the bottom so the scene stays visible
        //    while the user converses about it. Errors still take
        //    over fullscreen — they're meant to interrupt.
        if (scene is Scene.Idle || pipeline is PipelineState.Error) {
            PipelineFullscreenOverlay(pipeline)
        } else {
            PipelineHud(
                state = pipeline,
                onClose = viewModel::onCloseConversation,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Close button: only meaningful while a session is active. Hidden
        // when the pipeline is at rest (Idle — already "closed") and on
        // Error (a tap there already returns to Idle, no separate close
        // needed).
        val sessionActive =
            pipeline !is PipelineState.Idle && pipeline !is PipelineState.Error
        if (sessionActive) {
            IconButton(
                onClick = viewModel::onCloseConversation,
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

@Composable
private fun SceneLayer(scene: Scene) {
    when (scene) {
        Scene.Idle -> ClockScene()
        is Scene.Calendar -> CalendarScene(scene)
    }
}

/**
 * Full-screen pipeline overlay. Used when the active scene is the
 * clock (or for errors regardless of scene), so we can take over the
 * whole canvas with the conversation UX.
 */
@Composable
private fun PipelineFullscreenOverlay(state: PipelineState) {
    when (state) {
        // No overlay — the scene below shows through.
        PipelineState.Idle -> Unit
        PipelineState.Connecting -> ConnectingScreen()
        PipelineState.Listening -> ListeningScreen()
        is PipelineState.Processing ->
            ProcessingScreen(userTranscript = state.userTranscript)
        is PipelineState.Responding ->
            RespondingScreen(
                userTranscript = state.userTranscript,
                kiwiTranscript = state.kiwiTranscript,
            )
        is PipelineState.Error -> ErrorScreen(message = state.message)
    }
}
