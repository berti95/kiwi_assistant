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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kiwi.assistant.ui.CalendarEvent
import com.kiwi.assistant.ui.FactoidItem
import com.kiwi.assistant.ui.HomeSnapshot
import com.kiwi.assistant.ui.NowPlayingChip
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
    updateStatus: String? = null,
) {
    val hasAgenda = snapshot?.eventsToday?.isNotEmpty() == true
    val hasTodos = snapshot?.todos?.isNotEmpty() == true
    val hasNowPlaying = snapshot?.nowPlaying != null
    val hasRecent = snapshot?.recentlyPlayed?.isNotEmpty() == true
    val hasContent = hasAgenda || hasTodos || hasNowPlaying || hasRecent

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
                    .padding(bottom = KiwiSpacing.sm),
                compact = true,
                weather = snapshot?.weather,
                nextAlarmMs = nextAlarmMs,
                onNextAlarmTap = onOpenAlarmList,
            )

            // #7 Chip contextual justo bajo el reloj compacto — se
            // pinta antes del pair de cards para que el usuario lo
            // vea de reojo sin buscarlo.
            HomeContextualHeader(
                events = snapshot?.eventsToday.orEmpty(),
            )
            snapshot?.factoid?.let { f ->
                Spacer(Modifier.height(KiwiSpacing.xs))
                FactoidLine(factoid = f)
            }
            Spacer(Modifier.height(KiwiSpacing.md))

            // #5 Barra "próximo evento en X min". Sólo aparece si hay
            // uno dentro de las próximas 2h — no roba espacio cuando
            // no aplica.
            val eventsForBar = snapshot?.eventsToday.orEmpty()
            if (eventsForBar.isNotEmpty()) {
                NextEventBar(events = eventsForBar)
                Spacer(Modifier.height(KiwiSpacing.sm))
            }

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
        // Tick por minuto para que el clasificador de estado
        // (past/active/upcoming) no se quede rancio mientras el
        // usuario está mirando la home. Reusa el mismo pulso que el
        // reloj para no doblar timers.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(60_000L - (System.currentTimeMillis() % 60_000L))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + 2.dp)) {
            events.take(MAX_AGENDA_ROWS).forEach { event ->
                EventRow(event, eventStatus(event, nowMs))
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

/**
 * Estado temporal de un evento dentro del día. ``Active`` marca los
 * que están sucediendo ahora mismo — el usuario lo verá con un chip
 * "▶ En curso" y un borde de acento (#4).
 */
private enum class EventStatus { Past, Active, Upcoming }

private fun eventStatus(event: CalendarEvent, nowMs: Long): EventStatus {
    if (event.allDay) {
        // All-day: si hoy cae dentro del rango, "Active"; si el rango
        // ya pasó (poco común porque el backend filtra por hoy), "Past".
        return runCatching {
            val start = LocalDate.parse(event.startsAt)
            val endExclusive = LocalDate.parse(event.endsAt)
            val today = LocalDate.now()
            if (today in start..endExclusive.minusDays(1)) EventStatus.Active
            else if (today.isAfter(endExclusive.minusDays(1))) EventStatus.Past
            else EventStatus.Upcoming
        }.getOrDefault(EventStatus.Upcoming)
    }
    return runCatching {
        val start = OffsetDateTime.parse(event.startsAt).toInstant().toEpochMilli()
        val end = OffsetDateTime.parse(event.endsAt).toInstant().toEpochMilli()
        when {
            nowMs < start -> EventStatus.Upcoming
            nowMs in start..end -> EventStatus.Active
            else -> EventStatus.Past
        }
    }.getOrDefault(EventStatus.Upcoming)
}

@Composable
private fun EventRow(event: CalendarEvent, status: EventStatus) {
    val isActive = status == EventStatus.Active
    val isPast = status == EventStatus.Past
    val slotColor = when {
        isActive -> Color(0xFF7AD97F)  // Verde suave para "en curso"
        isPast -> Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY)
        else -> Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY)
    }
    val titleColor = if (isPast) Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY)
        else Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY)
    val titleDecoration = if (isPast) TextDecoration.LineThrough else null

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isActive) {
            // Punto verde pulsando (visualmente: fixed dot, sin
            // animación por ahora — animación cuesta recomposition
            // en un loop de un tick por segundo).
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7AD97F)),
            )
            Spacer(Modifier.width(KiwiSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatEventSlot(event),
                    color = slotColor,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (isActive) {
                    Spacer(Modifier.width(KiwiSpacing.sm))
                    Text(
                        text = "En curso",
                        color = Color(0xFF7AD97F),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF7AD97F).copy(alpha = 0.15f))
                            .padding(horizontal = KiwiSpacing.sm, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = event.title,
                color = titleColor,
                style = MaterialTheme.typography.titleMedium.copy(
                    textDecoration = titleDecoration,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Barra sobre la agenda con "próximo evento en X min" cuando hay uno
 * dentro de las próximas dos horas (#5). Se auto-actualiza cada minuto
 * (mismo tick que el reloj). Devuelve null si no hay evento próximo
 * relevante — la home queda igual que antes.
 */
@Composable
private fun NextEventBar(events: List<CalendarEvent>) {
    var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tickMs = System.currentTimeMillis()
            val toNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(toNextMinute)
        }
    }
    val next = remember(events, tickMs) {
        nextUpcomingEventWithinHours(events, tickMs, hoursAhead = 2)
    } ?: return
    val (event, startMs) = next
    val minutes = ((startMs - tickMs) / 60_000L).coerceAtLeast(0).toInt()
    val label = when {
        minutes <= 1 -> "en 1 min"
        minutes < 60 -> "en $minutes min"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "en ${h}h" else "en ${h}h${m}"
        }
    }
    // Progreso hacia el evento: 0 = ahora mismo, 1 = hace 2h. Se
    // interpreta al revés para que la barra crezca a medida que se
    // acerca (más útil visualmente que decrezca).
    val totalWindowMs = 2 * 60 * 60 * 1000L
    val progress = (1f - (startMs - tickMs).toFloat() / totalWindowMs).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.sm),
    ) {
        Text(
            text = "Próximo · ${event.title} · $label",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Color(0xFF7AD97F)),
            )
        }
    }
}

private fun nextUpcomingEventWithinHours(
    events: List<CalendarEvent>,
    nowMs: Long,
    hoursAhead: Int,
): Pair<CalendarEvent, Long>? {
    val horizonMs = nowMs + hoursAhead * 60L * 60_000L
    var best: Pair<CalendarEvent, Long>? = null
    for (ev in events) {
        if (ev.allDay) continue
        val startMs = runCatching {
            OffsetDateTime.parse(ev.startsAt).toInstant().toEpochMilli()
        }.getOrNull() ?: continue
        if (startMs < nowMs || startMs > horizonMs) continue
        if (best == null || startMs < best!!.second) best = ev to startMs
    }
    return best
}

/**
 * Chip contextual sobre el ClockBlock (#7). Cambia según hora del día
 * + qué hay hoy — para dar un latido de vida al home cuando el resto
 * del contenido es estático. Ejemplos:
 *   - Mañana laborable: "Buenos días · Café ☕"
 *   - Mañana fin de semana: "Buen finde"
 *   - Mediodía con evento próximo: "Comer pronto"
 *   - Tarde tranquila: "Tarde tranquila"
 *   - Noche con alarma: "Buenas noches"
 */
private fun contextualSuggestion(
    hour: Int,
    weekday: java.time.DayOfWeek,
    hasEventNext2h: Boolean,
    hasEventsToday: Boolean,
): String? {
    val isWeekend = weekday == java.time.DayOfWeek.SATURDAY ||
        weekday == java.time.DayOfWeek.SUNDAY
    return when (hour) {
        in 5..8 -> if (isWeekend) "Buen finde · ☕ tranquila" else "Buenos días · ☕"
        in 9..11 -> if (hasEventNext2h) "Prepara el próximo" else "Buena mañana"
        in 12..14 -> if (hasEventNext2h) "Come pronto" else "Hora de comer 🍽️"
        in 15..17 -> if (hasEventsToday) "Media tarde" else "Tarde tranquila"
        in 18..20 -> if (isWeekend) "Buena tarde de fin de semana" else "Casi noche"
        in 21..23 -> "Buenas noches 🌙"
        in 0..4 -> "Todavía de madrugada"
        else -> null
    }
}

@Composable
private fun ContextualChip(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = KiwiOpacity.BADGE_BG))
            .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.xs + 2.dp),
    )
}

/**
 * Envoltorio con tick por minuto para el chip contextual — así el
 * saludo pasa de "Buenos días" a "Hora de comer" sin necesidad de
 * refresh manual. Devuelve un Box en lugar de null cuando no hay
 * texto para no colapsar el padding del contenedor padre.
 */
@Composable
private fun HomeContextualHeader(events: List<CalendarEvent>) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val nowMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val hasEventNext2h = nextUpcomingEventWithinHours(events, nowMs, 2) != null
    val text = contextualSuggestion(
        hour = now.hour,
        weekday = now.dayOfWeek,
        hasEventNext2h = hasEventNext2h,
        hasEventsToday = events.isNotEmpty(),
    ) ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ContextualChip(text = text)
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
 * Línea única "Hoy en 1223 · En Francia…" bajo el chip contextual,
 * apretada al top del home para no competir con el chip "Habla con
 * Kiwi" en el borde inferior. Sin fondo — sólo texto secundario para
 * que sea un latido de información, no una card gorda.
 */
@Composable
private fun FactoidLine(factoid: FactoidItem) {
    Text(
        text = "Hoy en ${factoid.year} · ${factoid.text}",
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KiwiSpacing.md),
    )
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

