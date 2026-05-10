package com.kiwi.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
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
import com.kiwi.assistant.ui.scenes.AlarmListScene
import com.kiwi.assistant.ui.scenes.AlarmRingingScene
import com.kiwi.assistant.ui.scenes.BrowseYouTubeScene
import com.kiwi.assistant.ui.scenes.CalendarScene
import com.kiwi.assistant.ui.scenes.HomeScene
import com.kiwi.assistant.ui.scenes.NowPlayingScene
import com.kiwi.assistant.ui.scenes.PlaylistListScene
import com.kiwi.assistant.ui.scenes.ShoppingListScene
import com.kiwi.assistant.ui.scenes.TimerScene
import com.kiwi.assistant.ui.scenes.TodoListScene
import com.kiwi.assistant.ui.scenes.UsageStatsScene
import com.kiwi.assistant.ui.scenes.VideoListScene
import com.kiwi.assistant.ui.scenes.VideoPlayerScene
import com.kiwi.assistant.ui.screens.ConnectingScreen
import com.kiwi.assistant.ui.screens.ErrorScreen
import com.kiwi.assistant.ui.screens.ListeningScreen
import com.kiwi.assistant.ui.screens.ProcessingScreen
import com.kiwi.assistant.ui.screens.ReconnectingScreen
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
    val homeSnapshot by viewModel.homeSnapshot.collectAsState()
    val eventBanner by viewModel.eventSoonBanner.collectAsState()
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
        // Base layer: the active scene (clock, calendar, video list,
        // playback, browse). Each scene is full-screen.
        SceneLayer(
            scene = scene,
            homeSnapshot = homeSnapshot,
            onExitScene = viewModel::onExitScene,
            onTodoTap = viewModel::onTodoTap,
            onOpenTodoList = viewModel::onOpenTodoList,
            onTimerDismiss = viewModel::onTimerDismiss,
            onAlarmDismiss = viewModel::onAlarmDismiss,
            onAlarmSnooze = viewModel::onAlarmSnooze,
            onShoppingTap = viewModel::onShoppingTap,
            onOpenUsageStats = { viewModel.onOpenUsageStats() },
        )

        // Overlay layer. Two modes:
        //
        // 1. Scene is Idle (the clock) → fullscreen overlay covers
        //    everything. Same UX as before scenes existed.
        // 2. Scene is non-Idle (calendar, now-playing, …) → compact
        //    HUD pinned at the bottom so the scene stays visible
        //    while the user converses about it. Errors still take
        //    over fullscreen — they're meant to interrupt.
        // Reconnecting + Error are "important" enough that we always
        // take over the canvas with a fullscreen overlay, even if a
        // scene was visible — the user needs to know connectivity is
        // bad before they keep trying to talk to Kiwi.
        val pipelineWantsFullscreen =
            pipeline is PipelineState.Error || pipeline is PipelineState.Reconnecting
        if (scene is Scene.Idle || pipelineWantsFullscreen) {
            PipelineFullscreenOverlay(pipeline)
        } else if (pipeline !is PipelineState.Idle) {
            PipelineHud(
                state = pipeline,
                onClose = viewModel::onCloseConversation,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            // Non-Idle scene + Idle pipeline = the user is consuming
            // content (calendar, video, NowPlaying, …) without an
            // active conversation. The wake word ("hola kiwi") still
            // works in the background but it's neither obvious nor
            // 100% reliable over playback audio. Surface a tappable
            // chip so the user has a guaranteed way to re-engage.
            TalkAffordance(
                onTap = viewModel::onTap,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Close button: visible whenever there's something non-Idle to
        // close (a session, a non-Idle scene, or both). Tap = go home,
        // i.e. close the conversation if active AND reset the scene.
        // Hidden on scenes that ship their own top-start back arrow
        // (BrowseYouTube, VideoPlayer) so we don't stack two icons.
        val sessionActive =
            pipeline !is PipelineState.Idle && pipeline !is PipelineState.Error
        val sceneActive = scene !is Scene.Idle
        val sceneHasOwnBackButton =
            scene is Scene.BrowseYouTube || scene is Scene.VideoPlayer
        if ((sessionActive || sceneActive) && !sceneHasOwnBackButton) {
            IconButton(
                onClick = viewModel::onLongPress,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Volver al inicio",
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

        // Event-soon banner: top-anchored, encima de la escena pero
        // por debajo de los overlays fullscreen del pipeline. Aparece
        // cuando un evento del calendario empieza en <5 min y se
        // auto-cierra a los 30 s. La X manual también limpia.
        eventBanner?.let { banner ->
            EventSoonBannerView(
                banner = banner,
                onDismiss = viewModel::onDismissEventBanner,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun EventSoonBannerView(
    banner: EventSoonBanner,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = remember(banner.startsAt) {
        runCatching {
            java.time.OffsetDateTime.parse(banner.startsAt)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrDefault("")
    }
    val nowMs = System.currentTimeMillis()
    val startMs = remember(banner.startsAt) {
        runCatching {
            java.time.OffsetDateTime.parse(banner.startsAt).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
    val minutesUntil = if (startMs > 0) {
        ((startMs - nowMs).coerceAtLeast(0L) / 60_000L).toInt()
    } else 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E3A5F))
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (minutesUntil <= 0) "Empieza ahora" else "Empieza en $minutesUntil min",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = listOfNotNull(
                    time.takeIf { it.isNotBlank() }?.let { "$it · ${banner.title}" }
                        ?: banner.title,
                ).first(),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            banner.location?.takeIf { it.isNotBlank() }?.let { loc ->
                Text(
                    text = loc,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cerrar aviso",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SceneLayer(
    scene: Scene,
    homeSnapshot: HomeSnapshot?,
    onExitScene: () -> Unit,
    onTodoTap: (TodoItem) -> Unit,
    onOpenTodoList: () -> Unit,
    onTimerDismiss: () -> Unit,
    onAlarmDismiss: (String) -> Unit,
    onAlarmSnooze: (String, Int) -> Unit,
    onShoppingTap: (ShoppingItem) -> Unit,
    onOpenUsageStats: () -> Unit,
) {
    when (scene) {
        Scene.Idle -> HomeScene(
            snapshot = homeSnapshot,
            onOpenTodoList = onOpenTodoList,
            onOpenUsageStats = onOpenUsageStats,
        )
        is Scene.Calendar -> CalendarScene(scene)
        is Scene.VideoList -> VideoListScene(scene)
        is Scene.PlaylistList -> PlaylistListScene(scene)
        is Scene.VideoPlayer -> VideoPlayerScene(scene, onExit = onExitScene)
        is Scene.BrowseYouTube -> BrowseYouTubeScene(scene, onExit = onExitScene)
        is Scene.NowPlaying -> NowPlayingScene(scene)
        is Scene.TodoList -> TodoListScene(scene, onTodoTap = onTodoTap)
        is Scene.Timer -> TimerScene(scene, onDismiss = onTimerDismiss)
        is Scene.AlarmList -> AlarmListScene(scene)
        is Scene.AlarmRinging -> AlarmRingingScene(
            scene = scene,
            onDismiss = onAlarmDismiss,
            onSnooze = onAlarmSnooze,
        )
        is Scene.ShoppingList -> ShoppingListScene(scene, onItemTap = onShoppingTap)
        is Scene.UsageStats -> UsageStatsScene(scene)
    }
}

/**
 * Tappable bottom-center chip that re-opens a Kiwi conversation
 * from inside a non-Idle scene. Backup for the wake word: even when
 * "hola kiwi" doesn't get through (e.g. the user is watching a
 * video, music is playing, or the mic is too far) the chip is one
 * tap away.
 */
@Composable
private fun TalkAffordance(onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                .clickable(onClick = onTap)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Habla con Kiwi",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleSmall,
            )
        }
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
        is PipelineState.Reconnecting ->
            ReconnectingScreen(attempt = state.attempt, maxAttempts = state.maxAttempts)
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
