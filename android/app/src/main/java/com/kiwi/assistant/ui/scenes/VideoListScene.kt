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
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.VideoItem
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Renders a list of YouTube videos — used for both search results
 * and playlist contents (same wire format, different ``title``).
 *
 * Voice flow: the user reads the list while Kiwi narrates a summary,
 * then says "pon el segundo" / "el de paella" → Gemini receives the
 * video_id catalogue from the previous tool response and calls
 * youtube_play to push a [Scene.VideoPlayer] over the top.
 */
@Composable
fun VideoListScene(scene: Scene.VideoList) {
    SceneScaffold(
        title = scene.title.ifBlank { "Videos" },
        subtitle = if (scene.videos.size == 1) "1 video" else "${scene.videos.size} videos",
        horizontalPadding = KiwiSpacing.xl + KiwiSpacing.xs,
        verticalPadding = KiwiSpacing.xxl,
    ) {
        Spacer(Modifier.size(KiwiSpacing.xs))
        if (scene.videos.isEmpty()) {
            EmptyState(message = "No hay videos.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
                itemsIndexed(scene.videos) { index, video ->
                    VideoRow(position = index + 1, video = video)
                }
            }
        }
    }
}

@Composable
private fun VideoRow(position: Int, video: VideoItem) {
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
        Thumbnail(url = video.thumbnailUrl, duration = video.durationLabel)
        Spacer(Modifier.width(KiwiSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (video.channel.isNotBlank()) {
                Spacer(Modifier.height(KiwiSpacing.xs))
                Text(
                    text = video.channel,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Numbered chip on the left of each row so the user can visually
 * count "1, 2, 3…" matching what they'd say to Kiwi ("pon el cuatro").
 */
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

@Composable
private fun Thumbnail(url: String?, duration: String?) {
    Box(
        modifier = Modifier
            .size(width = 180.dp, height = 100.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!duration.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = KiwiOpacity.OVERLAY))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = duration,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
