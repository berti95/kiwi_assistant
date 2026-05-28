package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
 * Spotify NowPlaying — pantalla tipo reproductor. La carátula manda:
 * grande y centrada, con un fondo difuminado de la misma portada para
 * darle ambiente (en vez del negro pelado). Debajo: título + artista +
 * álbum, barra de progreso que avanza en vivo, y una fila de controles
 * (⏮ ⏯ ⏭) que pegan directamente al backend vía /api/spotify/*.
 */
@Composable
fun NowPlayingScene(
    scene: Scene.NowPlaying,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Fondo ambiente: la misma carátula difuminada a pantalla
        // completa con un velo oscuro encima para que el texto y los
        // controles tengan contraste de sobra.
        if (scene.albumArtUrl != null) {
            AsyncImage(
                model = scene.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = KiwiSpacing.xxl, vertical = KiwiSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AlbumArt(url = scene.albumArtUrl)
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
            Spacer(Modifier.height(KiwiSpacing.lg))
            Controls(
                isPlaying = scene.isPlaying,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
            )
        }
    }
}

@Composable
private fun AlbumArt(url: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .aspectRatio(1f)
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(KiwiRadii.md),
                clip = false,
            )
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
    }
}

/**
 * Fila de controles del reproductor: anterior · play/pausa · siguiente.
 * El botón central es un círculo verde Spotify, más grande, que es la
 * acción principal; ⏮ ⏭ son pulsadores blancos a los lados.
 */
@Composable
private fun Controls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(0.6f),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideControl(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Anterior",
            onClick = onPrevious,
        )

        // Botón principal: play/pausa, círculo verde Spotify.
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.spotifyGreen)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                tint = Color.Black,
                modifier = Modifier.size(40.dp),
            )
        }

        SideControl(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Siguiente",
            onClick = onNext,
        )
    }
}

@Composable
private fun SideControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun ProgressBar(progressMs: Long, durationMs: Long) {
    val pct = (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth(0.6f),
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
