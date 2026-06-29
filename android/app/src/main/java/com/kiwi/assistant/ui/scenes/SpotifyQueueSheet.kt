package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.kiwi.assistant.network.SpotifyQueue
import com.kiwi.assistant.ui.SpotifyResultItem
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Bottom sheet con la cola actual de Spotify: pista en curso + las
 * siguientes en orden de reproducción. Read-only por ahora: la Web
 * API no expone reorder de cola, así que el usuario tendría que
 * limpiar manualmente y encolar de nuevo (fuera de alcance de S6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyQueueSheet(
    queue: SpotifyQueue?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111111),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KiwiSpacing.lg, vertical = KiwiSpacing.md),
        ) {
            Text(
                text = "Cola",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.padding(bottom = KiwiSpacing.md),
            )
            when {
                queue == null -> CenterMessage("Cargando…")
                queue.current == null && queue.upcoming.isEmpty() ->
                    CenterMessage("Cola vacía.")
                else -> {
                    queue.current?.let { current ->
                        Text(
                            text = "Sonando ahora",
                            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = KiwiSpacing.xs),
                        )
                        QueueRow(item = current)
                        Spacer(Modifier.height(KiwiSpacing.md))
                    }
                    if (queue.upcoming.isNotEmpty()) {
                        Text(
                            text = "A continuación",
                            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = KiwiSpacing.xs),
                        )
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(KiwiSpacing.xs),
                            contentPadding = PaddingValues(bottom = KiwiSpacing.md),
                        ) {
                            items(items = queue.upcoming) { item ->
                                QueueRow(item = item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KiwiSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun QueueRow(item: SpotifyResultItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .padding(KiwiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG)),
        ) {
            if (item.albumArtUrl != null) {
                AsyncImage(
                    model = item.albumArtUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.artist.isNotBlank()) {
                Text(
                    text = item.artist,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
