package com.kiwi.assistant.ui.scenes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kiwi.assistant.ui.theme.rememberAlbumDominantColor
import com.kiwi.assistant.ui.theme.spotifyGreen
import androidx.compose.ui.graphics.Brush

/**
 * Spotify NowPlaying card — V2 interactiva.
 *
 * Diferencias frente a la V1 (que era de solo lectura):
 *  - Botones reales tappables: ⏮ ▶/⏸ ⏭, shuffle, repeat, like.
 *  - Slider de seek con drag (callback al soltar para no spamear).
 *  - Botón de "device" abre el bottom sheet de Spotify Connect.
 *  - Tick local de progreso lo provee el ViewModel actualizando la
 *    scene varias veces por segundo entre eventos SSE.
 *
 * Wire-up del ViewModel: TODOS los callbacks son opcionales (default
 * no-op) para que la scene aún funcione sin cliente vivo —
 * importante porque a veces nos llega desde Gemini sin que el repo
 * esté observando.
 */
@Composable
fun NowPlayingScene(
    scene: Scene.NowPlaying,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleLike: () -> Unit = {},
    onOpenDeviceSheet: () -> Unit = {},
    onOpenQueueSheet: () -> Unit = {},
    autoWakeStatus: String? = null,
) {
    // Fondo de gradiente con el color dominante de la carátula.
    // Cae al negro plano si la imagen aún no ha cargado o no hay url.
    val accent = rememberAlbumDominantColor(scene.albumArtUrl)
    val gradient = Brush.verticalGradient(
        colors = listOf(
            accent.copy(alpha = 0.55f),
            Color.Black,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            // El TalkAffordance vive en BottomCenter del Box raíz de
            // KiwiScreen (chip de ~50 dp + 24 dp de respiración). En
            // NowPlaying reservamos ~120 dp abajo para que la fila
            // secundaria (♥ · device · cola) quede claramente por
            // encima del chip de voz.
            .padding(
                start = KiwiSpacing.xxl,
                end = KiwiSpacing.xxl,
                top = KiwiSpacing.lg,
                bottom = KiwiSpacing.xxl + KiwiSpacing.xxl + KiwiSpacing.lg,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
            if (autoWakeStatus != null) {
                Spacer(Modifier.height(KiwiSpacing.md))
                AutoWakeBanner(text = autoWakeStatus)
            }
            if (scene.durationMs > 0) {
                Spacer(Modifier.height(KiwiSpacing.lg - 4.dp))
                ProgressSlider(
                    progressMs = scene.progressMs,
                    durationMs = scene.durationMs,
                    onSeek = onSeek,
                )
            }
            Spacer(Modifier.height(KiwiSpacing.md))
            TransportRow(
                isPlaying = scene.isPlaying,
                shuffle = scene.shuffle,
                repeatState = scene.repeatState,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )
            Spacer(Modifier.height(KiwiSpacing.md))
            SecondaryRow(
                liked = scene.liked,
                deviceName = scene.device?.name,
                onToggleLike = onToggleLike,
                onOpenDeviceSheet = onOpenDeviceSheet,
                onOpenQueueSheet = onOpenQueueSheet,
            )
        }
    }
}

@Composable
private fun AlbumArt(url: String?) {
    // Tamaño adaptativo: la carátula nunca debe exceder el ~40% del
    // ancho ni el ~45% del alto disponible. En portrait domina el
    // ancho; en landscape (el modo natural del Pixel Tablet en dock)
    // domina el alto y antes los controles se salían fuera de la
    // pantalla porque la carátula al 38% del ancho era casi tan alta
    // como toda la zona útil de la columna.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth * 0.40f, maxHeight * 0.45f)
        Box(
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(KiwiRadii.md))
                .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG)),
            contentAlignment = Alignment.Center,
        ) {
        // AnimatedContent fade-crossfade entre carátulas al cambiar de
        // pista. contentKey por url para que el mismo url no re-anime.
        AnimatedContent(
            targetState = url,
            contentKey = { it ?: "_none" },
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith
                    fadeOut(animationSpec = tween(300))
            },
            label = "album-fade",
        ) { current ->
            if (current != null) {
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Spacer(Modifier.fillMaxSize())
            }
        }
        }
    }
}

/**
 * Banner discreto con spinner + texto, usado mientras
 * ``playWithAutoWake`` está intentando despertar Spotify y
 * reintentando el play tras un tap en el Hub / Results.
 */
@Composable
private fun AutoWakeBanner(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.spotifyGreen,
        )
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProgressSlider(
    progressMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    // El slider mantiene su propio estado mientras se arrastra, y
    // suelta el valor en ``onValueChangeFinished`` (un solo POST). Si
    // el usuario no está arrastrando, el valor lo lleva la scene.
    var dragging by remember { mutableStateOf(false) }
    var draftMs by remember(progressMs) { mutableStateOf(progressMs.toFloat()) }
    val effectiveMs = if (dragging) draftMs.toLong() else progressMs
    Column(
        modifier = Modifier.fillMaxWidth(0.7f),
    ) {
        Slider(
            value = (if (dragging) draftMs else progressMs.toFloat())
                .coerceIn(0f, durationMs.toFloat()),
            onValueChange = {
                dragging = true
                draftMs = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(draftMs.toLong())
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.spotifyGreen,
                activeTrackColor = MaterialTheme.colorScheme.spotifyGreen,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatMs(effectiveMs),
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

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeatState: String,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (shuffle) "Quitar aleatorio" else "Aleatorio",
                tint = if (shuffle) {
                    MaterialTheme.colorScheme.spotifyGreen
                } else {
                    Color.White.copy(alpha = KiwiOpacity.ICON_DIM)
                },
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Anterior",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(40.dp),
            )
        }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause
                              else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pausa" else "Play",
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Siguiente",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onCycleRepeat, modifier = Modifier.size(48.dp)) {
            val (icon, tint, desc) = when (repeatState) {
                "track" -> Triple(
                    Icons.Filled.RepeatOne,
                    MaterialTheme.colorScheme.spotifyGreen,
                    "Repetir pista",
                )
                "context" -> Triple(
                    Icons.Filled.Repeat,
                    MaterialTheme.colorScheme.spotifyGreen,
                    "Repetir lista",
                )
                else -> Triple(
                    Icons.Filled.Repeat,
                    Color.White.copy(alpha = KiwiOpacity.ICON_DIM),
                    "Sin repetir",
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SecondaryRow(
    liked: Boolean?,
    deviceName: String?,
    onToggleLike: () -> Unit,
    onOpenDeviceSheet: () -> Unit,
    onOpenQueueSheet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onToggleLike, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (liked == true) Icons.Filled.Favorite
                              else Icons.Filled.FavoriteBorder,
                contentDescription = if (liked == true) "Quitar de favoritos" else "Me gusta",
                tint = if (liked == true) {
                    MaterialTheme.colorScheme.spotifyGreen
                } else {
                    Color.White.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(26.dp),
            )
        }
        DeviceChip(
            deviceName = deviceName,
            onClick = onOpenDeviceSheet,
        )
        IconButton(onClick = onOpenQueueSheet, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.QueueMusic,
                contentDescription = "Cola",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun DeviceChip(deviceName: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.spotifyGreen,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = deviceName?.takeIf { it.isNotBlank() } ?: "Sin dispositivo",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
