package com.kiwi.assistant.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Gradient vertical del cielo, coloreado según el `icon` que devuelve
 * el backend (clear / partly_cloudy / cloudy / fog / rain / snow / storm).
 *
 * Compartido entre la vista de pared (`AmbientHomeScene`) y el home
 * regular (`HomeScene`). El home lo pinta más suave (mezclado con
 * negro y con un alpha bajo) para no competir con las cards; la
 * vista de pared lo pinta a saco. El helper devuelve el gradient a
 * intensidad plena — cada scene decide cómo mezclarlo.
 *
 * Todos terminan en negro abajo para que texto y cards blancos
 * sigan siendo legibles con cualquier clima.
 */
fun weatherBackgroundBrush(icon: String?): Brush = when (icon) {
    "clear" -> Brush.verticalGradient(
        listOf(Color(0xFF1E5B99), Color(0xFF0A2540), Color.Black),
    )
    "partly_cloudy" -> Brush.verticalGradient(
        listOf(Color(0xFF2E6EA1), Color(0xFF1B2F44), Color.Black),
    )
    "cloudy" -> Brush.verticalGradient(
        listOf(Color(0xFF4F5B66), Color(0xFF2A2F36), Color.Black),
    )
    "fog" -> Brush.verticalGradient(
        listOf(Color(0xFF6E7278), Color(0xFF32363B), Color.Black),
    )
    "rain" -> Brush.verticalGradient(
        listOf(Color(0xFF34506C), Color(0xFF1A2938), Color.Black),
    )
    "snow" -> Brush.verticalGradient(
        listOf(Color(0xFF7C93B0), Color(0xFF2B3849), Color.Black),
    )
    "storm" -> Brush.verticalGradient(
        listOf(Color(0xFF3A3548), Color(0xFF1A1826), Color.Black),
    )
    else -> Brush.verticalGradient(
        listOf(Color.Black, Color.Black),
    )
}

/**
 * Emoji Unicode grande que ilustra el clima; ``null`` si desconocido.
 */
fun weatherEmoji(icon: String?): String? = when (icon) {
    "clear" -> "☀️"
    "partly_cloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rain" -> "🌧️"
    "snow" -> "❄️"
    "storm" -> "⛈️"
    else -> null
}
