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

/**
 * Renders the user's own YouTube playlists. Voice flow: user asks
 * "qué playlists tengo", Kiwi narrates the list, user says "abre la
 * de música clásica" → Gemini calls youtube_playlist_items with
 * playlist_name and the screen swaps to a [Scene.VideoList].
 */
@Composable
fun PlaylistListScene(scene: Scene.PlaylistList) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 40.dp, vertical = 48.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(count = scene.playlists.size)
            Spacer(Modifier.height(20.dp))
            if (scene.playlists.isEmpty()) {
                EmptyPlaylists()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(scene.playlists) { idx, p ->
                        PlaylistRow(position = idx + 1, playlist = p)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int) {
    Column {
        Text(
            text = "Tus playlists",
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
            ),
        )
        Text(
            text = if (count == 1) "1 playlist" else "$count playlists",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun EmptyPlaylists() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No tienes playlists guardadas.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun PlaylistRow(position: Int, playlist: PlaylistItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PositionBadge(position)
        Spacer(Modifier.width(12.dp))
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
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (playlist.itemCount == 1) "1 video"
                       else "${playlist.itemCount} videos",
                color = Color.White.copy(alpha = 0.55f),
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
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
