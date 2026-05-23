package com.kiwi.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Compact pipeline-state HUD anchored to the bottom of the screen.
 *
 * Used when there's a non-Idle [Scene] underneath that the user
 * needs to keep seeing (calendar, now-playing, video, …). Renders as
 * a translucent rounded pill so the scene shows through above and
 * around it.
 *
 * For [PipelineState.Idle] the HUD renders nothing — at rest there's
 * no need for any indicator on top of the scene.
 */
@Composable
fun PipelineHud(
    state: PipelineState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is PipelineState.Idle) return
    if (state is PipelineState.Error) return  // handled fullscreen by router

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(start = 24.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // contentKey por clase: Listening → Processing → Responding
            // hace fade, pero las actualizaciones de transcript dentro
            // del mismo estado (Processing.userTranscript creciendo)
            // no — esas se reflejan instantáneas.
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(140))
                },
                label = "hud-leading",
            ) { current -> HudLeading(current) }
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(140))
                },
                label = "hud-caption",
                modifier = Modifier.weight(1f, fill = false),
            ) { current -> HudCaption(current, modifier = Modifier) }
            // Stop button — labelled because the user repeatedly missed
            // the previous icon-only X. "Detener" + Mic-off makes it
            // clear that tapping stops the listening.
            FilledTonalButton(
                onClick = onClose,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Detener")
            }
        }
    }
}

@Composable
private fun HudLeading(state: PipelineState) {
    when (state) {
        PipelineState.Connecting -> CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp,
            color = Color.White.copy(alpha = 0.85f),
        )
        PipelineState.Listening -> PulsingIcon(
            icon = Icons.Filled.Mic,
            tint = Color(0xFF7CD992),  // soft green
        )
        is PipelineState.Processing -> PulsingIcon(
            icon = Icons.Filled.HourglassTop,
            tint = Color.White.copy(alpha = 0.85f),
        )
        is PipelineState.Responding -> PulsingIcon(
            icon = Icons.Filled.GraphicEq,
            tint = Color(0xFF8BB7FF),  // soft blue
        )
        else -> Spacer(Modifier.size(22.dp))
    }
}

@Composable
private fun HudCaption(state: PipelineState, modifier: Modifier) {
    val text = captionFor(state) ?: return
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.92f),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun PulsingIcon(icon: ImageVector, tint: Color) {
    val transition = rememberInfiniteTransition(label = "hud-pulse")
    val target by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hud-pulse-alpha",
    )
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(22.dp)
            .alpha(target),
    )
}

private fun captionFor(state: PipelineState): String? = when (state) {
    PipelineState.Connecting -> "Conectando…"
    PipelineState.Listening -> "Escuchando…"
    is PipelineState.Processing ->
        state.userTranscript.takeIf { it.isNotBlank() } ?: "Pensando…"
    is PipelineState.Responding ->
        state.kiwiTranscript.takeIf { it.isNotBlank() } ?: "Respondiendo…"
    else -> null
}
