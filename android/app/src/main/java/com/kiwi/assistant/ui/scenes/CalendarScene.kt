package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val HEADER_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
private val SHORT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d", SPANISH)
private val EVENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

/**
 * Renders a list of upcoming calendar events. The Compose surface is
 * driven entirely by the data the backend's `calendar_list_events`
 * tool emitted; nothing here talks to Google directly.
 */
@Composable
fun CalendarScene(scene: Scene.Calendar) {
    SceneScaffold(
        title = "Agenda",
        subtitle = subtitleFor(scene.period, scene.events.size),
    ) {
        Spacer(Modifier.size(KiwiSpacing.sm))
        if (scene.events.isEmpty()) {
            EmptyState(message = emptyMessageFor(scene.period))
        } else {
            EventList(events = scene.events)
        }
    }
}

/**
 * Render the events grouped by their start day with section
 * headers ("Hoy", "Mañana", "Viernes 8 de mayo"…). For single-day
 * periods (today/tomorrow) there's only one group, so the header
 * is mostly redundant — kept anyway for visual consistency and
 * because the period subtitle is meta ("Hoy · 3 eventos") while
 * the day header is structural.
 */
@Composable
private fun EventList(events: List<CalendarEvent>) {
    val grouped = remember(events) {
        // groupBy preserves insertion order in Kotlin's
        // LinkedHashMap, and the backend already returns events
        // sorted by startTime — so chronological ordering survives.
        events.groupBy { dayKeyFor(it) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm)) {
        grouped.forEach { (day, dayEvents) ->
            item(key = "header-${day ?: "unknown"}") {
                DaySectionHeader(day)
            }
            items(
                items = dayEvents,
                key = { it.startsAt + it.title },
            ) { event ->
                EventRow(event)
            }
        }
    }
}

@Composable
private fun DaySectionHeader(day: LocalDate?) {
    val text = day?.let { dayLabel(it) } ?: ""
    if (text.isBlank()) return
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(
            start = KiwiSpacing.xs,
            top = KiwiSpacing.sm + KiwiSpacing.xs,
            bottom = KiwiSpacing.xs,
        ),
    )
}

private fun dayKeyFor(event: CalendarEvent): LocalDate? = runCatching {
    if (event.allDay) {
        LocalDate.parse(event.startsAt)
    } else {
        OffsetDateTime.parse(event.startsAt).toLocalDate()
    }
}.getOrNull()

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Hoy"
        today.plusDays(1) -> "Mañana"
        today.minusDays(1) -> "Ayer"
        else -> day.format(HEADER_DATE_FORMATTER).titlecaseFirst()
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatEventTime(event),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.size(KiwiSpacing.xs))
            Text(
                text = event.title,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleLarge,
            )
            event.location?.takeIf { it.isNotBlank() }?.let { loc ->
                Spacer(Modifier.size(2.dp))
                Text(
                    text = loc,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
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
            formatAllDay(
                start = LocalDate.parse(event.startsAt),
                endExclusive = LocalDate.parse(event.endsAt),
            )
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

/**
 * Render an all-day event's date slot.
 *
 * Google Calendar's all-day events use an EXCLUSIVE end date —
 * "2026-05-08 → 2026-05-11" means the event spans May 8/9/10. We
 * convert to the inclusive last day for display.
 *
 * Single-day → "Viernes 8 de mayo · Todo el día"
 * Same-month range → "Del viernes 8 al domingo 10 de mayo · Todo el día"
 * Cross-month range → "Del viernes 31 de octubre al lunes 3 de noviembre"
 */
private fun formatAllDay(start: LocalDate, endExclusive: LocalDate): String {
    val endInclusive = endExclusive.minusDays(1).coerceAtLeast(start)
    if (start == endInclusive) {
        val day = start.format(HEADER_DATE_FORMATTER).titlecaseFirst()
        return "$day · Todo el día"
    }
    val sameMonth = start.month == endInclusive.month && start.year == endInclusive.year
    val startLabel =
        if (sameMonth) start.format(SHORT_DATE_FORMATTER).titlecaseFirst()
        else start.format(HEADER_DATE_FORMATTER).titlecaseFirst()
    val endLabel = endInclusive.format(HEADER_DATE_FORMATTER).titlecaseFirst()
    return "Del $startLabel al $endLabel · Todo el día"
}

private fun String.titlecaseFirst(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(SPANISH) else it.toString() }
