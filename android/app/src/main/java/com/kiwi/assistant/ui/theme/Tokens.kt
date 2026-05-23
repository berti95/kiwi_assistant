package com.kiwi.assistant.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing tokens. Use these instead of hardcoded dp values so the
 * tablet UI stays visually rhythmic. Scale follows a soft 4-8-16-…
 * progression — close enough to Material defaults but tuned to a 10"
 * screen viewed from across the room.
 */
object KiwiSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val huge = 56.dp
}

/** Corner radii. ``md`` is the canonical "kiwi card" radius. */
object KiwiRadii {
    val sm = 12.dp
    val md = 20.dp
    val lg = 28.dp
}

/**
 * Alpha values used across text/backgrounds. Avoids the slightly
 * different 0.92/0.95, 0.4/0.45 inconsistencies that grew over time.
 */
object KiwiOpacity {
    const val TEXT_PRIMARY = 0.92f
    const val TEXT_SECONDARY = 0.55f
    const val TEXT_TERTIARY = 0.45f
    const val CARD_BG = 0.04f
    const val BADGE_BG = 0.06f
    const val ROW_BG = 0.06f
    const val OVERLAY = 0.78f
    const val ICON_DIM = 0.45f
    const val DISABLED = 0.30f
}

/**
 * Tablet-tuned typography sizes used outside the standard Material
 * scale. Anything that needs to be "readable from 2 m" lives here.
 */
object KiwiTypography {
    val clockIdle = 140.sp
    val clockCompact = 96.sp
    val clockAmbient = 240.sp
    val ambientInfo = 56.sp
    val ambientDate = 32.sp
}
