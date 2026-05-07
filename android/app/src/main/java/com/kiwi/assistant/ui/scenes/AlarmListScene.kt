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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.AlarmItem
import com.kiwi.assistant.ui.Scene
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 56.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(count = scene.items.size)
            Spacer(Modifier.height(24.dp))
            if (scene.items.isEmpty()) {
                Empty()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(scene.items, key = { it.id }) { AlarmRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Header(count: Int) {
    Column {
        Text(
            text = "Despertadores",
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
            ),
        )
        Text(
            text = if (count == 1) "1 activo" else "$count activos",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Empty() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Sin despertadores activos.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun AlarmRow(item: AlarmItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTime(item.firesAtMs),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = formatDayLabel(item.firesAtMs, item.label),
                color = Color.White.copy(alpha = 0.55f),
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
