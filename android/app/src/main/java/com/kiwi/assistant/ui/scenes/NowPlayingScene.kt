package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import com.kiwi.assistant.ui.theme.spotifyGreen

/**
 * Spotify NowPlaying card. Album art is the centerpiece — large
 * square, centered — with title + artist + album under it. Bottom
 * has a thin progress bar (static; the user can ask "qué suena"
 * again to refresh; the live-progress refresh is a Fase 8 thing
 * paired with the unified MediaController).
 */
@Composable
fun NowPlayingScene(scene: Scene.NowPlaying) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = KiwiSpacing.xxl, vertical = KiwiSpacing.huge),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AlbumArt(
                url = scene.albumArtUrl,
                isPlaying = scene.isPlaying,
            )
            Spacer(Modifier.height(KiwiSpacing.lg + KiwiSpacing.xs))
            Text(
                text = scene.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (scene.artist.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = scene.artist,
                    color = Color.White.copy(alpha = KiwiOpacity.OVERLAY),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scene.album.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = scene.album,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scene.durationMs > 0) {
                Spacer(Modifier.height(KiwiSpacing.lg - 4.dp))
                ProgressBar(
                    progressMs = scene.progressMs,
                    durationMs = scene.durationMs,
                )
            }
        }
    }
}

@Composable
private fun AlbumArt(url: String?, isPlaying: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.55f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(KiwiRadii.md))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG)),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Translucent play/pause indicator overlay so the user can
        // tell at a glance whether the music is sounding or paused.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.PlayArrow
                              else Icons.Filled.Pause,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ProgressBar(progressMs: Long, durationMs: Long) {
    val pct = (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth(0.55f),
    ) {
        LinearProgressIndicator(
            progress = { pct },
            color = MaterialTheme.colorScheme.spotifyGreen,
            trackColor = Color.White.copy(alpha = 0.12f),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatMs(progressMs),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = formatMs(durationMs),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
