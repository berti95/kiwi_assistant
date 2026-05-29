package com.kiwi.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
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
import com.kiwi.assistant.ui.scenes.AmbientHomeScene
import com.kiwi.assistant.ui.scenes.BrowseYouTubeScene
import com.kiwi.assistant.ui.scenes.CalendarScene
import com.kiwi.assistant.ui.scenes.HomeScene
import com.kiwi.assistant.ui.scenes.NowPlayingScene
import com.kiwi.assistant.ui.scenes.PlaylistListScene
import com.kiwi.assistant.ui.scenes.ShoppingListScene
import com.kiwi.assistant.ui.scenes.TimerScene
import com.kiwi.assistant.ui.scenes.PlansListScene
import com.kiwi.assistant.ui.scenes.TodoListScene
import com.kiwi.assistant.ui.scenes.UsageStatsScene
import com.kiwi.assistant.ui.scenes.VideoListScene
import com.kiwi.assistant.ui.scenes.VideoPlayerScene
import com.kiwi.assistant.ui.theme.nightDim
import com.kiwi.assistant.ui.theme.rememberNightModeActive
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
    val canGoBack by viewModel.canGoBack.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val updateStatus by viewModel.updateStatus.collectAsState()
    val spotifyError by viewModel.spotifyError.collectAsState()
    // Modo nocturno: el tablet vive en pared cerca del dormitorio.
    // Entre 22:00 y 07:00 aplicamos un overlay marrón cálido para
    // que no moleste. La transición dura 1s al cruzar el umbral.
    val nightActive = rememberNightModeActive()

    // Banda inferior reservada para el chip / HUD. La escena se
    // pinta encima de esta franja para que su contenido NO pueda
    // solaparse con la affordance de "Habla con Kiwi" ni con el HUD
    // de conversación. Antes cada escena tenía que esquivarlos a
    // mano (la home con un offset de 88dp en el chip, las demás
    // simplemente se solapaban); ahora el contenedor manda y las
    // escenas no se enteran.
    val bottomBand = bottomBandFor(scene, pipeline)
    val sceneBottomInset = if (bottomBand == BottomBand.None) 0.dp else BottomBandHeight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nightDim(nightActive)
            // Tap en el fondo no hace nada — abrir conversación
            // requiere pulsar explícitamente el botón "Habla con
            // Kiwi". Excepción: en Scene.Ambient un tap cualquiera
            // saca al usuario de la vista de pared. Long-press se
            // mantiene como atajo "vuelve a home" desde cualquier
            // escena.
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (scene === Scene.Ambient) viewModel.exitAmbient()
                },
                onLongClick = viewModel::onLongPress,
            ),
    ) {
        // Base layer: the active scene (clock, calendar, video list,
        // playback, browse). Each scene is full-screen. Envuelto en
        // AnimatedContent para que cambiar de scene haga un cross-fade
        // suave en vez del salto duro de antes. contentKey por clase
        // → re-render de la misma scene (TodoList con datos nuevos)
        // NO dispara animación; solo cambios de tipo de pantalla lo
        // hacen.
        AnimatedContent(
            targetState = scene,
            contentKey = { it::class },
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith
                    fadeOut(animationSpec = tween(180))
            },
            label = "scene-fade",
            modifier = Modifier.padding(bottom = sceneBottomInset),
        ) { current ->
            SceneLayer(
                scene = current,
                homeSnapshot = homeSnapshot,
                onExitScene = viewModel::onExitScene,
                onTodoTap = viewModel::onTodoTap,
                onAddTodo = viewModel::onAddTodo,
                onOpenTodoList = viewModel::onOpenTodoList,
                onTimerDismiss = viewModel::onTimerDismiss,
                onAlarmDismiss = viewModel::onAlarmDismiss,
                onAlarmSnooze = viewModel::onAlarmSnooze,
                onShoppingTap = viewModel::onShoppingTap,
                onOpenUsageStats = { viewModel.onOpenUsageStats() },
                onSelectUsagePeriod = { viewModel.onOpenUsageStats(it) },
                onOpenAlarmList = viewModel::onOpenAlarmList,
                onOpenCalendar = viewModel::onOpenCalendar,
                onOpenNowPlaying = viewModel::onOpenNowPlaying,
                onSpotifyPlayPause = viewModel::onSpotifyPlayPause,
                onSpotifyNext = viewModel::onSpotifyNext,
                onSpotifyPrevious = viewModel::onSpotifyPrevious,
                spotifyError = spotifyError,
                onDismissSpotifyError = viewModel::dismissSpotifyError,
                onOpenShoppingList = viewModel::onOpenShoppingList,
                onOpenPlansList = viewModel::onOpenPlansList,
                onAddPlan = viewModel::onAddPlan,
                onRemovePlan = viewModel::onRemovePlan,
                onRenovarGoogle = viewModel::onRenovarGoogleClick,
                onCheckForUpdate = viewModel::onCheckForUpdate,
                updateStatus = updateStatus,
            )
        }

        // Overlay layer. `bottomBand` ya resolvió cuál de los cuatro
        // estados disjuntos toca (fullscreen / chip / hud / nada),
        // así que aquí solo pintamos.
        //
        // - Error / Reconnecting → overlay fullscreen, sea cual sea
        //   la escena: el usuario tiene que ver el problema antes de
        //   seguir intentando hablar con Kiwi.
        // - Ambient + pipeline Idle → pantalla "limpia" — sin
        //   affordance del mic ni overlay. Wake word sigue activa;
        //   tap o palabra disparan el ViewModel, que saca de Ambient.
        // - Pipeline Idle (resto de escenas) → chip "Habla con Kiwi"
        //   en la banda inferior.
        // - Pipeline activo + scene Idle (home) → fullscreen overlay
        //   con Listening / Processing / Responding como antes.
        // - Pipeline activo + scene no-Idle → HUD compacto en la
        //   banda inferior para que la escena (agenda, video, etc.)
        //   siga visible.
        when (bottomBand) {
            BottomBand.Chip -> TalkAffordance(
                onTap = viewModel::onTap,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            BottomBand.Hud -> PipelineHud(
                state = pipeline,
                onClose = viewModel::onCloseConversation,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            BottomBand.None -> if (
                pipeline is PipelineState.Error ||
                pipeline is PipelineState.Reconnecting ||
                (scene is Scene.Idle && pipeline !is PipelineState.Idle)
            ) {
                PipelineFullscreenOverlay(pipeline)
            }
        }

        // Top-left chrome: back arrow (if there's a previous scene in
        // the stack) + home button. Both stacked horizontally when the
        // user is deep in navigation (Home → TodoList → Calendar). On
        // scenes that ship their own back arrow (WebViews) we hide
        // ours to avoid duplicate icons.
        //
        // - Back arrow → vm.onBack(): pop al histórico (TodoList →
        //   Calendar → back vuelve a TodoList).
        // - Home button → vm.onLongPress(): reset directo, hace el
        //   mismo trabajo que el long-press en el fondo.
        val sessionActive =
            pipeline !is PipelineState.Idle && pipeline !is PipelineState.Error
        // Ambient se trata como "sin scene activa" para el chrome:
        // sin back arrow ni close X, sin contar como navegación.
        val sceneActive = scene !is Scene.Idle && scene !== Scene.Ambient
        val sceneHasOwnBackButton =
            scene is Scene.BrowseYouTube || scene is Scene.VideoPlayer
        if ((sessionActive || sceneActive) && !sceneHasOwnBackButton) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                if (canGoBack) {
                    IconButton(
                        onClick = viewModel::onBack,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(
                    onClick = viewModel::onLongPress,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (canGoBack) {
                            Icons.Default.Home
                        } else {
                            Icons.Default.Close
                        },
                        contentDescription = "Volver al inicio",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp),
                    )
                }
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
        // AnimatedVisibility para que entre/salga suave (expand+fade)
        // en vez del aparecer/desaparecer brusco de antes.
        AnimatedVisibility(
            visible = eventBanner != null,
            enter = expandVertically(animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
        ) {
            // Snapshot del banner cuando salta a null el composable
            // mantiene el último valor durante el exit transition.
            val current = eventBanner
            if (current != null) {
                EventSoonBannerView(
                    banner = current,
                    onDismiss = viewModel::onDismissEventBanner,
                )
            }
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
    onAddTodo: (String, TodoOwner, String?) -> Unit,
    onOpenTodoList: () -> Unit,
    onTimerDismiss: () -> Unit,
    onAlarmDismiss: (String) -> Unit,
    onAlarmSnooze: (String, Int) -> Unit,
    onShoppingTap: (ShoppingItem) -> Unit,
    onOpenUsageStats: () -> Unit,
    onSelectUsagePeriod: (String) -> Unit,
    onOpenAlarmList: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onSpotifyPlayPause: () -> Unit,
    onSpotifyNext: () -> Unit,
    onSpotifyPrevious: () -> Unit,
    spotifyError: String?,
    onDismissSpotifyError: () -> Unit,
    onOpenShoppingList: () -> Unit,
    onOpenPlansList: () -> Unit,
    onAddPlan: (String, String) -> Unit,
    onRemovePlan: (String) -> Unit,
    onRenovarGoogle: () -> Unit,
    onCheckForUpdate: () -> Unit,
    updateStatus: String?,
) {
    when (scene) {
        Scene.Idle -> HomeScene(
            snapshot = homeSnapshot,
            onOpenTodoList = onOpenTodoList,
            onOpenUsageStats = onOpenUsageStats,
            onOpenAlarmList = onOpenAlarmList,
            onOpenCalendar = onOpenCalendar,
            onOpenNowPlaying = onOpenNowPlaying,
            onOpenShoppingList = onOpenShoppingList,
            onOpenPlansList = onOpenPlansList,
            onCheckForUpdate = onCheckForUpdate,
            updateStatus = updateStatus,
        )
        Scene.Ambient -> AmbientHomeScene(snapshot = homeSnapshot)
        is Scene.Calendar -> CalendarScene(scene, onRenovarGoogle = onRenovarGoogle)
        is Scene.VideoList -> VideoListScene(scene)
        is Scene.PlaylistList -> PlaylistListScene(scene)
        is Scene.VideoPlayer -> VideoPlayerScene(scene, onExit = onExitScene)
        is Scene.BrowseYouTube -> BrowseYouTubeScene(scene, onExit = onExitScene)
        is Scene.NowPlaying -> NowPlayingScene(
            scene = scene,
            onPlayPause = onSpotifyPlayPause,
            onNext = onSpotifyNext,
            onPrevious = onSpotifyPrevious,
            errorMessage = spotifyError,
            onDismissError = onDismissSpotifyError,
        )
        is Scene.TodoList -> TodoListScene(
            scene = scene,
            onTodoTap = onTodoTap,
            onAddTodo = onAddTodo,
        )
        is Scene.Timer -> TimerScene(scene, onDismiss = onTimerDismiss)
        is Scene.AlarmList -> AlarmListScene(scene)
        is Scene.AlarmRinging -> AlarmRingingScene(
            scene = scene,
            onDismiss = onAlarmDismiss,
            onSnooze = onAlarmSnooze,
        )
        is Scene.ShoppingList -> ShoppingListScene(scene, onItemTap = onShoppingTap)
        is Scene.UsageStats -> UsageStatsScene(scene, onSelectPeriod = onSelectUsagePeriod)
        is Scene.PlansList -> PlansListScene(
            scene = scene,
            onAdd = onAddPlan,
            onRemove = onRemovePlan,
        )
    }
}

/**
 * Altura de la franja inferior reservada para chip o HUD. Cubre el
 * alto real del [TalkAffordance] (~48dp chip + 24dp margin) y el del
 * [PipelineHud] (~52dp pill + 16dp margin) con un par de dp de aire.
 */
private val BottomBandHeight = 80.dp

/**
 * Qué se pinta en la banda inferior según el cruce escena × pipeline.
 * Centralizamos aquí la decisión para que el contenedor reserve el
 * inset exacto y el `when` de overlays no se vuelva a desincronizar.
 */
private enum class BottomBand { Chip, Hud, None }

private fun bottomBandFor(scene: Scene, pipeline: PipelineState): BottomBand = when {
    // Error / Reconnecting tapan la pantalla entera: no hay banda.
    pipeline is PipelineState.Error || pipeline is PipelineState.Reconnecting -> BottomBand.None
    // Vista de pared en reposo: nada en pantalla.
    scene === Scene.Ambient && pipeline is PipelineState.Idle -> BottomBand.None
    // En reposo (cualquier otra escena): el chip "Habla con Kiwi".
    pipeline is PipelineState.Idle -> BottomBand.Chip
    // Home + conversación activa: overlay fullscreen, sin banda.
    scene is Scene.Idle -> BottomBand.None
    // Resto de escenas con conversación activa: HUD compacto.
    else -> BottomBand.Hud
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
