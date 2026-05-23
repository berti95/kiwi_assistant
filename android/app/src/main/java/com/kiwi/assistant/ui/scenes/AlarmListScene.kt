package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.AlarmItem
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)

/**
 * Lista de despertadores activos. Se empuja desde alarm_set /
 * alarm_cancel / alarm_list. El ViewModel reconcilia AlarmManager
 * en cuanto llega esta escena, así que ver "x alarmas" aquí es
 * sinónimo de "están armadas".
 */
@Composable
fun AlarmListScene(scene: Scene.AlarmList) {
    SceneScaffold(
        title = "Despertadores",
        subtitle = if (scene.items.size == 1) "1 activo" else "${scene.items.size} activos",
    ) {
        Spacer(Modifier.size(KiwiSpacing.sm))
        if (scene.items.isEmpty()) {
            EmptyState(message = "Sin despertadores activos.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
                items(scene.items, key = { it.id }) { AlarmRow(it) }
            }
        }
    }
}

@Composable
private fun AlarmRow(item: AlarmItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Alarm,
                contentDescription = null,
                tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(KiwiSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTime(item.firesAtMs),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = formatDayLabel(item.firesAtMs, item.label),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)

private fun formatDayLabel(ms: Long, label: String): String {
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val day = when (date) {
        today -> "Hoy"
        today.plusDays(1) -> "Mañana"
        else -> date.format(DAY_FMT).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(SPANISH) else it.toString()
        }
    }
    return if (label.isBlank()) day else "$day · $label"
}
