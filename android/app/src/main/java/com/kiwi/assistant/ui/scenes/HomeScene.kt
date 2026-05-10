package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.WeatherInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val SPANISH = Locale("es", "ES")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", SPANISH)
private val EVENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

/**
 * Para eventos all-day multi-día. Sin "EEEE," delante del mes para
 * que entre en una sola línea junto a "Hasta el "; si la fecha es
 * en el mismo año vuela el año.
 */
private val ALL_DAY_END_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)

private const val MAX_AGENDA_ROWS = 5
private const val MAX_TODO_ROWS = 5

/**
 * The default Idle screen for the tablet.
 *
 * When ``snapshot`` is null (first paint) or fully empty (no events,
 * no TODOs, no music), it degrades into the bare clock — same UX as
 * the old ClockScene, no regression.
 *
 * When there's content, the layout is:
 *   - Top band: clock + date.
 *   - Bottom: two cards side by side (agenda left, TODOs right).
 *   - If music is playing, a slim chip across the bottom.
 */
@Composable
fun HomeScene(
    snapshot: HomeSnapshot?,
    onOpenTodoList: () -> Unit = {},
    onOpenUsageStats: () -> Unit = {},
) {
    val hasAgenda = snapshot?.eventsToday?.isNotEmpty() == true
    val hasTodos = snapshot?.todos?.isNotEmpty() == true
    val hasNowPlaying = snapshot?.nowPlaying != null
    val hasContent = hasAgenda || hasTodos || hasNowPlaying

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!hasContent) {
            ClockBlock(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                weather = snapshot?.weather,
            )
            UsageChip(
                onClick = onOpenUsageStats,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 36.dp),
        ) {
            ClockBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                compact = true,
                weather = snapshot?.weather,
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AgendaCard(
                    events = snapshot?.eventsToday.orEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                TodosCard(
                    items = snapshot?.todos.orEmpty(),
                    onOpen = onOpenTodoList,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            snapshot?.nowPlaying?.let { chip ->
                Spacer(Modifier.height(16.dp))
                NowPlayingBar(chip)
            }
        }

        UsageChip(
            onClick = onOpenUsageStats,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun UsageChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.QueryStats,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Uso",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ClockBlock(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    weather: WeatherInfo? = null,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(msToNextMinute)
        }
    }

    val time = TIME_FORMATTER.format(now)
    val date = DATE_FORMATTER.format(now).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(SPANISH) else it.toString()
    }

    val timeSize = if (compact) 96.sp else 140.sp

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = time,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = timeSize,
                    fontWeight = FontWeight.Thin,
                ),
            )
            Text(
                text = date,
                color = Color.White.copy(alpha = 0.5f),
                style = if (compact) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium,
            )
            if (weather != null) {
                Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                WeatherLine(weather = weather, compact = compact)
            }
        }
    }
}

@Composable
private fun WeatherLine(weather: WeatherInfo, compact: Boolean) {
    val tempLabel = "${weather.temperatureC.toInt()}°"
    val text = listOf(
        weatherEmoji(weather.icon),
        tempLabel,
        weather.description,
    ).filter { it.isNotBlank() }.joinToString("  ")
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.55f),
        style = if (compact) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.headlineSmall,
    )
}

/**
 * Map the backend's coarse icon string to a single emoji. Stays in
 * sync with ``weather._icon`` on the Python side. We render an emoji
 * (rather than a vector drawable) because the home is glanceable —
 * single glyph + temperature is enough.
 */
private fun weatherEmoji(icon: String): String = when (icon) {
    "clear" -> "☀️"
    "partly_cloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rain" -> "🌧️"
    "snow" -> "🌨️"
    "storm" -> "⛈️"
    else -> ""
}

@Composable
private fun AgendaCard(events: List<CalendarEvent>, modifier: Modifier = Modifier) {
    DashboardCard(modifier = modifier) {
        CardTitle("Hoy", subtitle = subtitleForAgenda(events))
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            CardEmpty("Nada en la agenda.")
            return@DashboardCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            events.take(MAX_AGENDA_ROWS).forEach { event ->
                EventRow(event)
            }
        }
    }
}

@Composable
private fun TodosCard(
    items: List<TodoItem>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = items.filter { !it.completed }
    DashboardCard(modifier = modifier.clickable { onOpen() }) {
        CardTitle(
            "Pendientes",
            subtitle = if (pending.isEmpty()) "Sin tareas" else
                if (pending.size == 1) "1 pendiente" else "${pending.size} pendientes",
        )
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            CardEmpty("Nada apuntado.")
            return@DashboardCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Pending first; if there's room, fill with recently
            // completed so the card feels lived-in.
            val display = (pending + items.filter { it.completed }).take(MAX_TODO_ROWS)
            display.forEach { item -> TodoRow(item) }
        }
    }
}

@Composable
private fun NowPlayingBar(chip: NowPlayingChip) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            chip.albumArtUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Suena ahora",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = listOfNotNull(
                    chip.title.takeIf { it.isNotBlank() },
                    chip.artist.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- internal pieces ------------------------------------------------

@Composable
private fun DashboardCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.92f),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Light,
        ),
    )
    Text(
        text = subtitle,
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CardEmpty(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    Column {
        Text(
            text = formatEventSlot(event),
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = event.title,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (item.completed) Color.White.copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.6f),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.text,
            color = Color.White.copy(alpha = if (item.completed) 0.4f else 0.92f),
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
            ),
            // 2 lines on the home card lets longer captures peek without
            // dominating the layout — the user can tap the card to jump
            // to the full TodoList scene if they want everything.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun subtitleForAgenda(events: List<CalendarEvent>): String {
    if (events.isEmpty()) return "Día tranquilo"
    return if (events.size == 1) "1 evento" else "${events.size} eventos"
}

/**
 * Compact time slot for an event row in the home dashboard.
 *
 * - Timed events: "09:00–09:15".
 * - All-day single-day: "Todo el día".
 * - All-day multi-día: "Hasta el [día]" si aún quedan días, o
 *   "Último día" cuando hoy es el cierre del rango. Sin esto el
 *   usuario veía "Todo el día" tanto para uno de un día como para
 *   uno de 13, dando la falsa sensación de que terminaba hoy.
 *
 * Falls back to the raw start string on parse failure so the row
 * nunca queda en blanco.
 */
private fun formatEventSlot(event: CalendarEvent): String {
    if (event.allDay) return formatAllDaySlot(event)
    return runCatching {
        val start = OffsetDateTime.parse(event.startsAt)
        val end = OffsetDateTime.parse(event.endsAt)
        "${EVENT_TIME_FORMATTER.format(start)}–${EVENT_TIME_FORMATTER.format(end)}"
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(event.startsAt).format(EVENT_TIME_FORMATTER)
        }.getOrElse { event.startsAt }
    }
}

/**
 * "Todo el día" para eventos de un solo día; para los multi-día,
 * algo que dé contexto sobre cuándo termina:
 *  - "Último día" cuando hoy es la fecha de cierre.
 *  - "Hasta el viernes 20 de mayo" en cualquier otro día del rango.
 *
 * Google Calendar devuelve `endsAt` EXCLUSIVO en all-day events
 * (un evento del 8 al 21 dura 8/9/…/20). Se ajusta a inclusivo
 * antes de comparar / formatear.
 */
private fun formatAllDaySlot(event: CalendarEvent): String {
    return runCatching {
        val start = LocalDate.parse(event.startsAt)
        val endExclusive = LocalDate.parse(event.endsAt)
        val endInclusive = endExclusive.minusDays(1).coerceAtLeast(start)
        if (start == endInclusive) return "Todo el día"
        val today = LocalDate.now()
        if (today == endInclusive) return "Último día"
        val label = endInclusive.format(ALL_DAY_END_FORMATTER)
        "Hasta el $label"
    }.getOrElse { "Todo el día" }
}

