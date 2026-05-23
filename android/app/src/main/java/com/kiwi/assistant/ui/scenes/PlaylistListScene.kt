package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.kiwi.assistant.ui.PlaylistItem
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Renders the user's own YouTube playlists. Voice flow: user asks
 * "qué playlists tengo", Kiwi narrates the list, user says "abre la
 * de música clásica" → Gemini calls youtube_playlist_items with
 * playlist_name and the screen swaps to a [Scene.VideoList].
 */
@Composable
fun PlaylistListScene(scene: Scene.PlaylistList) {
    SceneScaffold(
        title = "Tus playlists",
        subtitle = if (scene.playlists.size == 1) "1 playlist" else "${scene.playlists.size} playlists",
        horizontalPadding = KiwiSpacing.xl + KiwiSpacing.xs,
        verticalPadding = KiwiSpacing.xxl,
    ) {
        Spacer(Modifier.size(KiwiSpacing.xs))
        if (scene.playlists.isEmpty()) {
            EmptyState(message = "No tienes playlists guardadas.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
                itemsIndexed(scene.playlists) { idx, p ->
                    PlaylistRow(position = idx + 1, playlist = p)
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(position: Int, playlist: PlaylistItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .padding(KiwiSpacing.sm + KiwiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PositionBadge(position)
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        Box(
            modifier = Modifier
                .size(width = 140.dp, height = 80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            if (playlist.thumbnailUrl != null) {
                AsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(KiwiSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(KiwiSpacing.xs))
            Text(
                text = if (playlist.itemCount == 1) "1 video"
                       else "${playlist.itemCount} videos",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PositionBadge(position: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = position.toString(),
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
