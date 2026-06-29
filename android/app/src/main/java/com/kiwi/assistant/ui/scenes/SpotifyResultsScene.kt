package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.SpotifyResultItem
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Lista tappable de resultados de Spotify.
 *
 * Un solo composable parametrizado por ``scene.kind`` ("track" /
 * "album" / "artist" / "playlist"). El layout cambia ligeramente
 * según el tipo (carátula cuadrada vs. circular para artistas).
 *
 * Tap → onItemTap (reproducir). Long-press → onItemLongPress
 * (típicamente añadir a cola). El IconButton lateral lanza la misma
 * acción que un tap pero con afordancia visual explícita.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyResultsScene(
    scene: Scene.SpotifyResults,
    onItemTap: (SpotifyResultItem) -> Unit = {},
    onItemLongPress: (SpotifyResultItem) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                horizontal = KiwiSpacing.xl,
                vertical = KiwiSpacing.xl,
            ),
    ) {
        Text(
            text = scene.title,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(start = KiwiSpacing.md, bottom = KiwiSpacing.md),
        )
        if (scene.items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No hay resultados.",
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = KiwiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = scene.items, key = { it.uri }) { item ->
                    SpotifyResultRow(
                        item = item,
                        kind = scene.kind,
                        onTap = { onItemTap(item) },
                        onLongPress = { onItemLongPress(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotifyResultRow(
    item: SpotifyResultItem,
    kind: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.md),
    ) {
        AlbumThumb(url = item.albumArtUrl, isArtist = kind == "artist")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when (kind) {
                "track" -> buildString {
                    if (item.artist.isNotBlank()) append(item.artist)
                    if (item.album.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(item.album)
                    }
                }
                "album" -> item.artist
                "playlist" -> buildString {
                    if (item.owner.isNotBlank()) append(item.owner)
                    if (item.itemCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${item.itemCount} pistas")
                    }
                }
                "artist" -> ""  // sin subtítulo natural; podríamos meter géneros si los pidiéramos
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onTap, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Reproducir",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun AlbumThumb(url: String?, isArtist: Boolean) {
    val shape = if (isArtist) CircleShape else RoundedCornerShape(KiwiRadii.sm)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
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
