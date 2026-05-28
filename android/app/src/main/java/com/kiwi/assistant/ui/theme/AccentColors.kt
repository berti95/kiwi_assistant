package com.kiwi.assistant.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Accent colors that don't fit cleanly into Material's primary/
 * secondary slots but need to be theme-consistent across the app.
 *
 * Each is exposed as an extension property on ``ColorScheme`` so call
 * sites read like ``MaterialTheme.colorScheme.kiwiError`` instead of
 * hardcoding ``Color(0xFF...)``. Adding/changing a brand color is one
 * edit here instead of grepping the whole codebase.
 *
 * The values match what the codebase already uses — this is a
 * factoring pass, not a redesign.
 */

/** Microphone "live" indicator green (slightly different from primary). */
val ColorScheme.micActive: Color
    get() = Color(0xFF7CD992)

/** Processing/thinking accent (cool blue). */
val ColorScheme.processing: Color
    get() = Color(0xFF8BB7FF)

/** Error / destructive feedback red. */
val ColorScheme.kiwiError: Color
    get() = Color(0xFFFF6B6B)

/** Spotify brand green — used for the NowPlaying chip + cover frame. */
val ColorScheme.spotifyGreen: Color
    get() = Color(0xFF1DB954)

/** Event-soon banner background (navy). */
val ColorScheme.eventBanner: Color
    get() = Color(0xFF1E3A5F)

/** Night-mode warm overlay tint. */
val ColorScheme.nightWarmth: Color
    get() = Color(0xFF1A0E00)

// ---- TODO sections / due-date badges --------------------------------
// Acentos para la pantalla Pendientes. Tonos suaves para no chillar
// sobre fondo oscuro; los badges de fecha (vencida / hoy) usan un
// fondo de color saturado discreto + texto en blanco / negro para
// contraste sin estridencias.

/** Sage green — sección "Para mí" (tareas del usuario). */
val ColorScheme.todoMineAccent: Color
    get() = Color(0xFF7FB39C)

/** Soft purple — sección "Para Kiwi" (encargos para la IA). */
val ColorScheme.todoKiwiAccent: Color
    get() = Color(0xFFB084FF)

/** Badge de fecha vencida. Saturado pero no estridente. */
val ColorScheme.todoOverdue: Color
    get() = Color(0xFFE57373)

/** Badge "vence hoy". */
val ColorScheme.todoDueToday: Color
    get() = Color(0xFFFFB74D)
