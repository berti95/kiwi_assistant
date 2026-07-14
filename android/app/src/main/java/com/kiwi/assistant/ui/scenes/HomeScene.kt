package com.kiwi.assistant.ui.scenes

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
import com.kiwi.assistant.ui.PostIt
import com.kiwi.assistant.ui.SpotifyResultItem
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.WeatherInfo
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import com.kiwi.assistant.ui.theme.rememberAlbumDominantColor
import com.kiwi.assistant.ui.theme.weatherBackgroundBrush
import com.kiwi.assistant.ui.theme.weatherEmoji
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
    onOpenSpotifyHub: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onSpotifyResultTap: (SpotifyResultItem, String) -> Unit = { _, _ -> },
    onAddPostIt: () -> Unit = {},
    onRemovePostIt: (PostIt) -> Unit = {},
    updateStatus: String? = null,
) {
    val hasAgenda = snapshot?.eventsToday?.isNotEmpty() == true
    val hasTodos = snapshot?.todos?.isNotEmpty() == true
    val hasNowPlaying = snapshot?.nowPlaying != null
    val hasRecent = snapshot?.recentlyPlayed?.isNotEmpty() == true
    val hasPostits = snapshot?.postits?.isNotEmpty() == true
    val hasContent = hasAgenda || hasTodos || hasNowPlaying || hasRecent || hasPostits

    val nextAlarmMs = snapshot?.alarms
        ?.map { it.firesAtMs }
        ?.filter { it > System.currentTimeMillis() }
        ?.minOrNull()
    val alarmsCount = snapshot?.alarms?.size ?: 0

    // #8: Fondo dinámico del home según clima. Recolectamos el mismo
    // gradient que la Ambient view pero lo pintamos sobre negro con
    // un alpha muy bajo (0.35): en la home queremos "un sabor" del
    // cielo, no ahogar las cards. Sin snapshot → negro plano.
    val weatherIcon = snapshot?.weather?.icon
    val weatherBrush = weatherBackgroundBrush(weatherIcon)
    // #2: Tinte del home cuando suena algo — color dominante de la
    // carátula en un radial suave, arriba a la izquierda. Devuelve
    // negro cuando no hay URL, así que el overlay se auto-oculta.
    val albumTint = rememberAlbumDominantColor(snapshot?.nowPlaying?.albumArtUrl)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Fondo de cielo: 35% de alpha para no competir con las
        // cards blancas semitransparentes de la home.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(weatherBrush, alpha = 0.35f),
        )
        if (snapshot?.nowPlaying != null) {
            // Halo cálido del álbum en el rincón superior-izquierdo,
            // fundido con Screen-like alpha. Sólo se ve cuando suena
            // algo, así que el idle "clásico" no cambia.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                albumTint.copy(alpha = 0.28f),
                                Color.Transparent,
                            ),
                            radius = 1200f,
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = KiwiSpacing.xl,
                    vertical = KiwiSpacing.xl + KiwiSpacing.xs,
                ),
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
                    error = snapshot?.eventsTodayError,
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
                NowPlayingBar(
                    chip = chip,
                    isPlaying = true,  // Backend sólo emite chip cuando is_playing
                    onOpen = onOpenNowPlaying,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                )
            }

            // #6 Recently played: 6 tracks tap-to-play. Sólo se pinta
            // si hay historial — cuentas nuevas sin scope no lo tienen.
            if (hasRecent) {
                Spacer(Modifier.height(KiwiSpacing.md))
                RecentlyPlayedStrip(
                    items = snapshot?.recentlyPlayed.orEmpty(),
                    onOpenAll = onOpenSpotifyHub,
                    onTap = { onSpotifyResultTap(it, "track") },
                )
            }

            // #12 Post-its: notas rápidas, siempre visibles con un
            // botón "+" para añadir. Aunque estén vacíos pintamos la
            // fila con el "+" — es la única forma de descubrir la
            // feature sin abrir el manual.
            Spacer(Modifier.height(KiwiSpacing.md))
            PostItsRow(
                items = snapshot?.postits.orEmpty(),
                onAdd = onAddPostIt,
                onRemove = onRemovePostIt,
            )
        }

        Spacer(Modifier.height(KiwiSpacing.md))
        QuickActionsRow(
            onOpenShoppingList = onOpenShoppingList,
            onOpenCalendar = onOpenCalendar,
            onOpenAlarmList = onOpenAlarmList,
            onOpenUsageStats = onOpenUsageStats,
            onOpenSpotifyHub = onOpenSpotifyHub,
            onCheckForUpdate = onCheckForUpdate,
            updateStatus = updateStatus,
            alarmsCount = alarmsCount,
        )
        }  // Column
    }  // Box (contenedor con fondos)
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
    onOpenSpotifyHub: () -> Unit,
    onCheckForUpdate: () -> Unit,
    updateStatus: String?,
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
            icon = Icons.Default.MusicNote,
            label = "Música",
            onClick = onOpenSpotifyHub,
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
        // El label refleja el progreso del chequeo cuando hay uno en
        // curso (Buscando… / Ya al día / Actualizando…); en reposo
        // muestra "Actualizar".
        QuickActionChip(
            icon = Icons.Default.Refresh,
            label = updateStatus ?: "Actualizar",
            onClick = onCheckForUpdate,
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
            .animateContentSize()
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

/**
 * Línea de clima bajo el reloj. En el modo "empty home" (no
 * compact) se ve full: emoji + temperatura actual + descripción
 * + fila con max/min y "lluvia XX%". En modo compact — cuando hay
 * cards de agenda / TODOs / música — cae a una sola línea con
 * los datos clave para no comerse espacio.
 *
 * Los datos "hoy" (max/min, prob. lluvia) pueden llegar null
 * (backend viejo o Open-Meteo caído) y en ese caso la row extra
 * simplemente no aparece: la línea principal es suficiente y
 * evita mostrar dashes vacíos.
 */
@Composable
private fun WeatherLine(weather: WeatherInfo, compact: Boolean) {
    val emoji = weatherEmoji(weather.icon).orEmpty()
    val tempLabel = "${weather.temperatureC.toInt()}°"
    val hasForecast = weather.tempMaxC != null && weather.tempMinC != null
    val forecastLabel = if (hasForecast) {
        "${weather.tempMaxC!!.toInt()}° / ${weather.tempMinC!!.toInt()}°"
    } else null
    val rainLabel = weather.precipitationProbabilityMax
        ?.takeIf { it >= 10 }
        ?.let { "💧 $it%" }

    if (compact) {
        // Todo en una línea: "☀️ 24°  ·  27°/16°  ·  💧 20%".
        val parts = listOfNotNull(
            listOfNotNull(emoji.takeIf { it.isNotBlank() }, tempLabel)
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
            forecastLabel,
            rainLabel,
        )
        Text(
            text = parts.joinToString("  ·  "),
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.titleMedium,
        )
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = listOf(emoji, tempLabel, weather.description)
                    .filter { it.isNotBlank() }
                    .joinToString("  "),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.headlineSmall,
            )
            val extras = listOfNotNull(forecastLabel, rainLabel)
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = extras.joinToString("  ·  "),
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun AgendaCard(
    events: List<CalendarEvent>,
    error: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardCard(modifier = modifier.clickable { onOpen() }) {
        if (error != null) {
            // Estado de error: el backend no pudo leer la agenda
            // (típicamente OAuth de Google caducado). Antes el
            // tablet fingía un día tranquilo; ahora se ve claro
            // que falla y el tap lleva a CalendarScene donde el
            // botón "Renovar Google" hace el resto.
            CardTitle("Agenda", subtitle = "Toca para renovar")
            Spacer(Modifier.height(KiwiSpacing.sm + KiwiSpacing.xs))
            CardEmpty("⚠️ No disponible")
            return@DashboardCard
        }
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

/**
 * "Suena ahora" en el home: la fila entera abre la NowPlayingScene
 * al tocarla, pero los dos IconButton finales interceptan el tap
 * (Row.clickable → child clickable prioriza al hijo) y actúan como
 * play/pause + next inline (#1). Con esto pausar la música desde
 * la home ya no requiere abrir la escena completa.
 */
@Composable
private fun NowPlayingBar(
    chip: NowPlayingChip,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
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
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Siguiente",
                tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            )
        }
    }
}

/**
 * Carrusel horizontal de "Reproducido recientemente" para tap-to-play
 * (#6). Cada tarjeta es cuadrada (80dp) con la carátula + una única
 * línea de título abajo. El tap del contenedor lleva al SpotifyHub
 * completo (via ``onOpenAll``) para escrollear más historial; el tap
 * de una tarjeta reproduce esa pista directamente.
 */
@Composable
private fun RecentlyPlayedStrip(
    items: List<SpotifyResultItem>,
    onOpenAll: () -> Unit,
    onTap: (SpotifyResultItem) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAll() }
                .padding(horizontal = KiwiSpacing.xs, vertical = KiwiSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Escuchado recientemente",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Ver todo →",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.sm),
        ) {
            items.take(6).forEach { track ->
                RecentTile(track = track, onTap = { onTap(track) })
            }
        }
    }
}

@Composable
private fun RecentTile(track: SpotifyResultItem, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .clickable { onTap() }
            .padding(KiwiSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(KiwiSpacing.sm))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            track.albumArtUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(KiwiSpacing.xs))
        Text(
            text = track.title,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Post-its de colores (#12): notas cortas ancladas al home. Fila
 * horizontal con scroll, cada uno con su color de pastel. El botón
 * "+" siempre visible al inicio para añadir uno nuevo (abre un
 * diálogo simple). Tap normal en un post-it: nada; tap en la X:
 * eliminarlo.
 */
@Composable
private fun PostItsRow(
    items: List<PostIt>,
    onAdd: () -> Unit,
    onRemove: (PostIt) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddPostItButton(onClick = onAdd)
        items.forEach { postit ->
            PostItCard(postit = postit, onRemove = { onRemove(postit) })
        }
    }
}

@Composable
private fun AddPostItButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Añadir post-it",
            tint = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun PostItCard(postit: PostIt, onRemove: () -> Unit) {
    val bg = postItColor(postit.color)
    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 96.dp)
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(bg),
    ) {
        Text(
            text = postit.text,
            color = Color.Black.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    horizontal = KiwiSpacing.sm,
                    vertical = KiwiSpacing.xs,
                ),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Quitar post-it",
                tint = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Color de fondo para cada post-it. Tonos pastel saturados-suaves
 * para que texto negro se lea bien sin dañar la vista sobre el
 * fondo oscuro del home. Cualquier color no listado cae a amarillo.
 */
private fun postItColor(color: String): Color = when (color) {
    "yellow" -> Color(0xFFFFE58A)
    "pink" -> Color(0xFFF7B7C3)
    "blue" -> Color(0xFFA8CFF0)
    "green" -> Color(0xFFB6E2B0)
    "orange" -> Color(0xFFFFC59B)
    "purple" -> Color(0xFFD3B8F0)
    else -> Color(0xFFFFE58A)
}

/**
 * Diálogo simple para añadir un post-it. Un ``TextField`` + chips de
 * colores + botones Cancelar / Guardar. Se muestra desde
 * [HomeScene] cuando el usuario toca el "+"; se cierra con back o
 * tras un guardado exitoso.
 */
@Composable
fun AddPostItDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("yellow") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo post-it") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(140) },
                    placeholder = { Text("¿Qué quieres recordar?") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    ),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("yellow", "pink", "blue", "green", "orange", "purple").forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(if (c == color) 34.dp else 28.dp)
                                .clip(CircleShape)
                                .background(postItColor(c))
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSubmit(text.trim(), color)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                ),
                enabled = text.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                ),
            ) { Text("Cancelar") }
        },
        containerColor = Color(0xFF141414),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
    )
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

