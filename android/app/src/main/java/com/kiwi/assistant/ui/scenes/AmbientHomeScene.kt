package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.AlarmItem
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
import com.kiwi.assistant.ui.WeatherInfo
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import com.kiwi.assistant.ui.theme.KiwiTypography
import com.kiwi.assistant.ui.theme.rememberAlbumDominantColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", SPANISH)
private val ALARM_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE", SPANISH)

/**
 * "Vista de pared" — el reloj domina la pantalla y debajo aparece
 * UNA sola pieza de info útil con esta prioridad:
 *
 *   1. Despertador en menos de 12h (lo más operacional del día).
 *   2. Evento del calendario en menos de 2h (estás a punto de
 *      tener algo).
 *   3. Música sonando (NowPlaying chip muy compacto).
 *   4. Clima actual (relleno, siempre hay algo).
 *
 * Sin chips, sin grid, sin quick actions. El tap en cualquier
 * sitio sale (lo gestiona [KiwiScreen]); la wake word también
 * sale automáticamente porque el pipeline pasa de Idle → Connecting.
 */
@Composable
fun AmbientHomeScene(snapshot: HomeSnapshot?) {
    val nowPlaying = snapshot?.nowPlaying
    if (nowPlaying != null) {
        AmbientMusicView(chip = nowPlaying)
    } else {
        AmbientClockView(snapshot)
    }
}

/**
 * Variante "cinemática" del Ambient cuando hay música sonando:
 * carátula enorme centrada, título + artista debajo, reloj pequeño
 * arriba a la derecha. Sin botones — el tap sale al Home normal (lo
 * gestiona [com.kiwi.assistant.ui.KiwiScreen]).
 */
@Composable
private fun AmbientMusicView(chip: NowPlayingChip) {
    val accent = rememberAlbumDominantColor(chip.albumArtUrl)
    val gradient = Brush.verticalGradient(
        colors = listOf(
            accent.copy(alpha = 0.45f),
            Color.Black,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .padding(KiwiSpacing.xxl),
    ) {
        // Reloj pequeño arriba derecha.
        SmallClock(
            modifier = Modifier
                .align(Alignment.TopEnd),
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(KiwiRadii.md))
                    .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG)),
                contentAlignment = Alignment.Center,
            ) {
                if (chip.albumArtUrl != null) {
                    AsyncImage(
                        model = chip.albumArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(KiwiSpacing.lg))
            Text(
                text = chip.title.takeIf { it.isNotBlank() } ?: "Sonando",
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = KiwiTypography.ambientInfo,
                    fontWeight = FontWeight.Light,
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chip.artist.isNotBlank()) {
                Spacer(Modifier.height(KiwiSpacing.sm))
                Text(
                    text = chip.artist,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SmallClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(msToNextMinute)
        }
    }
    Text(
        text = TIME_FORMATTER.format(now),
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.displaySmall.copy(
            fontWeight = FontWeight.Thin,
        ),
        modifier = modifier,
    )
}

@Composable
private fun AmbientClockView(snapshot: HomeSnapshot?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(KiwiSpacing.xxl),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
        ) {
            ClockHero()
            HighlightLine(snapshot)
        }
    }
}

@Composable
private fun ClockHero() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Tick justo en el cambio de minuto para evitar drift.
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(msToNextMinute)
        }
    }
    val time = TIME_FORMATTER.format(now)
    val date = DATE_FORMATTER.format(now).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(SPANISH) else it.toString()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = time,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = KiwiTypography.clockAmbient,
                fontWeight = FontWeight.Thin,
            ),
        )
        Spacer(Modifier.height(KiwiSpacing.md))
        Text(
            text = date,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = KiwiTypography.ambientDate,
                fontWeight = FontWeight.Light,
            ),
        )
    }
}

@Composable
private fun HighlightLine(snapshot: HomeSnapshot?) {
    val text = highlightFor(snapshot)
    if (text.isBlank()) return
    Text(
        text = text,
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
        style = MaterialTheme.typography.displaySmall.copy(
            fontSize = KiwiTypography.ambientInfo,
            fontWeight = FontWeight.Light,
        ),
        textAlign = TextAlign.Center,
    )
}

/**
 * Elige UNA sola línea de info útil siguiendo la prioridad descrita
 * en el doc del [AmbientHomeScene]. Devuelve string vacío si no hay
 * absolutamente nada que mostrar (caso raro: tablet recién arrancado
 * sin snapshot todavía).
 */
private fun highlightFor(snapshot: HomeSnapshot?): String {
    val nowMs = System.currentTimeMillis()
    snapshot?.alarms?.let { alarmHighlight(it, nowMs) }?.let { return it }
    snapshot?.eventsToday?.let { eventHighlight(it, nowMs) }?.let { return it }
    snapshot?.nowPlaying?.let { return musicHighlight(it) }
    snapshot?.weather?.let { return weatherHighlight(it) }
    return ""
}

private const val ALARM_HORIZON_MS = 12 * 60 * 60 * 1000L  // 12h
private const val EVENT_HORIZON_MS = 2 * 60 * 60 * 1000L   // 2h

private fun alarmHighlight(alarms: List<AlarmItem>, nowMs: Long): String? {
    val next = alarms
        .filter { it.firesAtMs in nowMs..(nowMs + ALARM_HORIZON_MS) }
        .minByOrNull { it.firesAtMs } ?: return null
    val zoned = Instant.ofEpochMilli(next.firesAtMs).atZone(ZoneId.systemDefault())
    val time = TIME_FORMATTER.format(zoned)
    val today = LocalDateTime.now().toLocalDate()
    val dayPart = when (zoned.toLocalDate()) {
        today -> ""
        today.plusDays(1) -> "mañana "
        else -> "${ALARM_DAY_FORMATTER.format(zoned)} ".lowercase(SPANISH)
    }
    val label = next.label.takeIf { it.isNotBlank() }
    return if (label != null) {
        "Despertador $dayPart$time · $label"
    } else {
        "Despertador $dayPart$time".trim()
    }
}

private fun eventHighlight(events: List<CalendarEvent>, nowMs: Long): String? {
    val next = events
        .mapNotNull { event ->
            val startMs = runCatching {
                OffsetDateTime.parse(event.startsAt).toInstant().toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            if (startMs < nowMs || startMs > nowMs + EVENT_HORIZON_MS) {
                return@mapNotNull null
            }
            event to startMs
        }
        .minByOrNull { it.second } ?: return null
    val (event, startMs) = next
    val time = TIME_FORMATTER.format(
        Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()),
    )
    return "${event.title} · $time"
}

private fun musicHighlight(chip: NowPlayingChip): String {
    val parts = listOfNotNull(
        chip.title.takeIf { it.isNotBlank() },
        chip.artist.takeIf { it.isNotBlank() },
    )
    if (parts.isEmpty()) return "♪ Suena algo"
    return "♪ ${parts.joinToString(" — ")}"
}

private fun weatherHighlight(weather: WeatherInfo): String {
    val temp = "${weather.temperatureC.toInt()}°"
    val desc = weather.description.takeIf { it.isNotBlank() }
    return if (desc != null) "$temp · $desc" else temp
}
