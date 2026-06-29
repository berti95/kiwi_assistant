package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.SpotifyHubSection
import com.kiwi.assistant.ui.SpotifyResultItem
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Pantalla "Música": varios carruseles horizontales con biblioteca,
 * recientes y descubrimiento. Diseñado para que el usuario explore
 * sin teclear ni hablar — tap en cualquier ítem reproduce.
 *
 * Cada sección viene del backend en una sola llamada paralelizada
 * (``SpotifyApi.fetchHub``). Las secciones vacías se omiten.
 */
@Composable
fun SpotifyHubScene(
    scene: Scene.SpotifyHub,
    onItemTap: (SpotifyResultItem, kind: String) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(
                start = KiwiSpacing.lg,
                end = KiwiSpacing.lg,
                top = KiwiSpacing.xl,
                bottom = KiwiSpacing.lg,
            ),
    ) {
        Text(
            text = "Música",
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = KiwiSpacing.lg),
        )
        if (scene.sections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Cargando…",
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(KiwiSpacing.lg),
                contentPadding = PaddingValues(bottom = KiwiSpacing.xl),
            ) {
                items(items = scene.sections, key = { it.id }) { section ->
                    HubSection(section = section, onItemTap = onItemTap)
                }
            }
        }
    }
}

@Composable
private fun HubSection(
    section: SpotifyHubSection,
    onItemTap: (SpotifyResultItem, kind: String) -> Unit,
) {
    Column {
        Text(
            text = section.title,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp, bottom = KiwiSpacing.sm),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.md),
            contentPadding = PaddingValues(end = KiwiSpacing.md),
        ) {
            items(items = section.items, key = { it.uri }) { item ->
                HubCard(
                    item = item,
                    kind = section.kind,
                    onTap = { onItemTap(item, section.kind) },
                )
            }
        }
    }
}

@Composable
private fun HubCard(
    item: SpotifyResultItem,
    kind: String,
    onTap: () -> Unit,
) {
    val cardWidth = 140.dp
    val shape = if (kind == "artist") CircleShape else RoundedCornerShape(KiwiRadii.sm)
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .clickable(onClick = onTap)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(cardWidth)
                .clip(shape)
                .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.albumArtUrl != null) {
                AsyncImage(
                    model = item.albumArtUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(KiwiSpacing.sm))
        Text(
            text = item.title,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (kind == "artist") TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        val subtitle = when (kind) {
            "track", "album" -> item.artist
            "playlist" -> if (item.itemCount > 0) "${item.itemCount} pistas" else item.owner
            else -> ""
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (kind == "artist") TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
