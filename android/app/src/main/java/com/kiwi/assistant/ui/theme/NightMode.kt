package com.kiwi.assistant.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * Horario del modo nocturno. Por defecto 22:00–07:00 — pensado para
 * un tablet en pared cerca del dormitorio.
 *
 * El rango admite cruzar medianoche (start > end). Si en algún
 * momento se quiere configurar desde una pantalla de ajustes,
 * persistir esto en DataStore y pasarlo a [rememberNightModeActive].
 */
data class NightModeConfig(
    val startHour: Int = 22,
    val endHour: Int = 7,
)

/**
 * State Compose-friendly del modo nocturno. Lee la hora local del
 * sistema y se re-evalúa cada minuto. Cuando cambia (cruzas el
 * umbral de las 22 o de las 7) el caller recompone y el overlay
 * arranca su transición.
 */
@Composable
fun rememberNightModeActive(config: NightModeConfig = NightModeConfig()): Boolean {
    var active by remember(config) {
        mutableStateOf(isNight(config, LocalDateTime.now()))
    }
    LaunchedEffect(config) {
        while (true) {
            // Reevaluar inmediatamente y luego cada minuto. Suficiente
            // resolución para una transición visual gradual (1s fade).
            active = isNight(config, LocalDateTime.now())
            delay(60_000L)
        }
    }
    return active
}

private fun isNight(config: NightModeConfig, now: LocalDateTime): Boolean {
    val hour = now.hour
    return if (config.startHour <= config.endHour) {
        hour in config.startHour until config.endHour
    } else {
        // Cruza medianoche: noche es desde startHour o hasta endHour.
        hour >= config.startHour || hour < config.endHour
    }
}

/**
 * Modifier que aplica una capa de atenuación cálida cuando [active]
 * está en true. No bloquea taps porque usa [drawWithContent] — los
 * gestos siguen pasando intactos al composable de debajo.
 *
 * El color marrón cálido (#1A0E00) sobre el dark theme negro produce
 * un look tipo "luz de vela" sin matar el contraste de los textos
 * blancos. Alpha 0.35 funciona bien en pruebas en pared a 2m; si
 * se nota demasiado mate, bajarlo a 0.25.
 *
 * La transición de día↔noche dura 1s para que no sea brusca cuando
 * el reloj cruza las 22:00 / 07:00 mientras el usuario mira el
 * tablet.
 */
@Composable
fun Modifier.nightDim(active: Boolean): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (active) NIGHT_OVERLAY_ALPHA else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "night-dim-alpha",
    )
    return this.drawWithContent {
        drawContent()
        if (alpha > 0f) {
            drawRect(color = NIGHT_OVERLAY_COLOR.copy(alpha = alpha))
        }
    }
}

private val NIGHT_OVERLAY_COLOR = Color(0xFF1A0E00)
private const val NIGHT_OVERLAY_ALPHA = 0.35f
