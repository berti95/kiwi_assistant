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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.ShoppingCart
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
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
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
    onOpenAlarmList: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    onOpenShoppingList: () -> Unit = {},
) {
    val hasAgenda = snapshot?.eventsToday?.isNotEmpty() == true
    val hasTodos = snapshot?.todos?.isNotEmpty() == true
    val hasNowPlaying = snapshot?.nowPlaying != null
    val hasContent = hasAgenda || hasTodos || hasNowPlaying

    val nextAlarmMs = snapshot?.alarms
        ?.map { it.firesAtMs }
        ?.filter { it > System.currentTimeMillis() }
        ?.minOrNull()
    val alarmsCount = snapshot?.alarms?.size ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = KiwiSpacing.xl, vertical = KiwiSpacing.xl + KiwiSpacing.xs),
    ) {
        // Empty home: clock takes most of the screen, quick
        // actions still pinned at the bottom so the user can
        // navigate into Compra / Alarmas / etc. without voice.
        if (!hasContent) {
            ClockBlock(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                weather = snapshot?.weather,
                nextAlarmMs = nextAlarmMs,
                onNextAlarmTap = onOpenAlarmList,
            )
        } else {
            ClockBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = KiwiSpacing.lg),
                compact = true,
                weather = snapshot?.weather,
                nextAlarmMs = nextAlarmMs,
                onNextAlarmTap = onOpenAlarmList,
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.lg - 4.dp),
            ) {
                AgendaCard(
                    events = snapshot?.eventsToday.orEmpty(),
                    onOpen = onOpenCalendar,
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
                Spacer(Modifier.height(KiwiSpacing.md))
                NowPlayingBar(chip = chip, onOpen = onOpenNowPlaying)
            }
        }

        Spacer(Modifier.height(KiwiSpacing.md))
        QuickActionsRow(
            onOpenShoppingList = onOpenShoppingList,
            onOpenCalendar = onOpenCalendar,
            onOpenAlarmList = onOpenAlarmList,
            onOpenUsageStats = onOpenUsageStats,
            alarmsCount = alarmsCount,
        )
    }
}

/**
 * Fila de accesos directos en la parte baja de la home. Cinco chips
 * con icono + label corto, espaciados uniformemente, para entrar a
 * scenes que antes solo eran accesibles por voz (Compra, Calendar
 * completo) o que vivían como chips sueltos arriba-izquierda
 * (Alarmas, Uso).
 *
 * Cada chip es siempre tap-eable — el contador de alarmas se
 * muestra solo si > 0; el resto siempre llevan a su scene aunque
 * esté vacía (para poder programar la primera tarea por voz desde
 * ahí, etc.).
 */
@Composable
private fun QuickActionsRow(
    onOpenShoppingList: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAlarmList: () -> Unit,
    onOpenUsageStats: () -> Unit,
    alarmsCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            KiwiSpacing.sm + KiwiSpacing.xs,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickActionChip(
            icon = Icons.Default.ShoppingCart,
            label = "Compra",
            onClick = onOpenShoppingList,
        )
        QuickActionChip(
            icon = Icons.Default.CalendarMonth,
            label = "Agenda",
            onClick = onOpenCalendar,
        )
        QuickActionChip(
            icon = Icons.Default.Alarm,
            label = when (alarmsCount) {
                0 -> "Alarmas"
                1 -> "1 alarma"
                else -> "$alarmsCount alarmas"
            },
            onClick = onOpenAlarmList,
        )
        QuickActionChip(
            icon = Icons.Default.QueryStats,
            label = "Uso",
            onClick = onOpenUsageStats,
        )
    }
}

@Composable
private fun QuickActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .clickable { onClick() }
            .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.sm + KiwiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(KiwiSpacing.sm))
        Text(
            text = label,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ClockBlock(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    weather: WeatherInfo? = null,
    nextAlarmMs: Long? = null,
    onNextAlarmTap: () -> Unit = {},
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
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
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
            if (nextAlarmMs != null) {
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                NextAlarmLine(
                    firesAtMs = nextAlarmMs,
                    compact = compact,
                    onTap = onNextAlarmTap,
                )
            }
        }
    }
}

@Composable
private fun NextAlarmLine(
    firesAtMs: Long,
    compact: Boolean,
    onTap: () -> Unit,
) {
    val instant = java.time.Instant.ofEpochMilli(firesAtMs)
    val zoned = instant.atZone(java.time.ZoneId.systemDefault())
    val time = zoned.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val date = zoned.toLocalDate()
    val today = java.time.LocalDate.now()
    val dayPart = when (date) {
        today -> ""
        today.plusDays(1) -> "mañana "
        else -> date.format(
            java.time.format.DateTimeFormatter.ofPattern("EEEE d ", SPANISH)
        )
    }
    Text(
        text = "🔔 Próxima alarma ${dayPart}${time}".trim(),
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
        style = if (compact) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(KiwiSpacing.sm))
            .clickable { onTap() }
            .padding(horizontal = KiwiSpacing.sm, vertical = KiwiSpacing.xs),
    )
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
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
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
private fun AgendaCard(
    events: List<CalendarEvent>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardCard(modifier = modifier.clickable { onOpen() }) {
        CardTitle("Hoy", subtitle = subtitleForAgenda(events))
        Spacer(Modifier.height(KiwiSpacing.sm + KiwiSpacing.xs))
        if (events.isEmpty()) {
            CardEmpty("Nada en la agenda.")
            return@DashboardCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + 2.dp)) {
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
        Spacer(Modifier.height(KiwiSpacing.sm + KiwiSpacing.xs))
        if (items.isEmpty()) {
            CardEmpty("Nada apuntado.")
            return@DashboardCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm)) {
            // Pending first; if there's room, fill with recently
            // completed so the card feels lived-in.
            val display = (pending + items.filter { it.completed }).take(MAX_TODO_ROWS)
            display.forEach { item -> TodoRow(item) }
        }
    }
}

@Composable
private fun NowPlayingBar(chip: NowPlayingChip, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.md))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .clickable { onOpen() }
            .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.sm + KiwiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(KiwiSpacing.sm))
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
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
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
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
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
            .clip(RoundedCornerShape(KiwiRadii.md))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .padding(KiwiSpacing.lg - 4.dp),
    ) {
        content()
    }
}

@Composable
private fun CardTitle(title: String, subtitle: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
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
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = KiwiSpacing.md),
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    Column {
        Text(
            text = formatEventSlot(event),
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = event.title,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
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
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        Text(
            text = item.text,
            color = Color.White.copy(
                alpha = if (item.completed) KiwiOpacity.TEXT_TERTIARY else KiwiOpacity.TEXT_PRIMARY,
            ),
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

