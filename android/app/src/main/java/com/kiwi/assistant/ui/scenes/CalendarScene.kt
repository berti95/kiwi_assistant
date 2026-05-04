package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.Scene
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val HEADER_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
private val EVENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

/**
 * Renders a list of upcoming calendar events. The Compose surface is
 * driven entirely by the data the backend's `calendar_list_events`
 * tool emitted; nothing here talks to Google directly.
 */
@Composable
fun CalendarScene(scene: Scene.Calendar) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 56.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(period = scene.period, count = scene.events.size)
            Spacer(Modifier.height(24.dp))

            if (scene.events.isEmpty()) {
                EmptyEvents(period = scene.period)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(scene.events) { event -> EventRow(event) }
                }
            }
        }
    }
}

@Composable
private fun Header(period: String, count: Int) {
    Column {
        Text(
            text = "Agenda",
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
            ),
        )
        Text(
            text = subtitleFor(period, count),
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun EmptyEvents(period: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emptyMessageFor(period),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatEventTime(event),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = event.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleLarge,
            )
            event.location?.takeIf { it.isNotBlank() }?.let { loc ->
                Spacer(Modifier.size(2.dp))
                Text(
                    text = loc,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun subtitleFor(period: String, count: Int): String {
    val noun = if (count == 1) "evento" else "eventos"
    return when (period) {
        "today" -> "Hoy · $count $noun"
        "tomorrow" -> "Mañana · $count $noun"
        "this_week" -> "Esta semana · $count $noun"
        "next_7_days" -> "Próximos 7 días · $count $noun"
        else -> "$count $noun"
    }
}

private fun emptyMessageFor(period: String): String = when (period) {
    "today" -> "Nada en la agenda hoy."
    "tomorrow" -> "Nada en la agenda mañana."
    "this_week" -> "Nada esta semana."
    "next_7_days" -> "Nada en los próximos 7 días."
    else -> "No hay eventos."
}

/**
 * Format an event's time slot in Spanish-friendly short form.
 *
 * Tries OffsetDateTime first (timed events ship with TZ offset);
 * falls back to LocalDate (all-day events ship YYYY-MM-DD); falls
 * back to the raw string if both parses fail so we never blow up on
 * unexpected wire payloads.
 */
private fun formatEventTime(event: CalendarEvent): String {
    if (event.allDay) {
        return runCatching {
            val date = LocalDate.parse(event.startsAt)
            "Todo el día · " + date.format(HEADER_DATE_FORMATTER)
                .replaceFirstChar { it.titlecase(SPANISH) }
        }.getOrElse { "Todo el día" }
    }
    return runCatching {
        val start = OffsetDateTime.parse(event.startsAt)
        val end = OffsetDateTime.parse(event.endsAt)
        val date = start.toLocalDate().format(HEADER_DATE_FORMATTER)
            .replaceFirstChar { it.titlecase(SPANISH) }
        "$date · ${EVENT_TIME_FORMATTER.format(start)}–${EVENT_TIME_FORMATTER.format(end)}"
    }.getOrElse {
        // Last-ditch: try raw LocalDateTime (no offset) so a backend
        // bug doesn't make the row blank.
        runCatching {
            LocalDateTime.parse(event.startsAt).format(EVENT_TIME_FORMATTER)
        }.getOrElse { event.startsAt }
    }
}
