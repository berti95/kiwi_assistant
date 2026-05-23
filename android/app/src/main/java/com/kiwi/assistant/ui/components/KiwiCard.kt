package com.kiwi.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Standard card container used across scenes and the Home dashboard.
 *
 * Encapsulates the "translucent block with rounded corners + padding"
 * pattern that was previously copy-pasted 30+ times. If a row needs a
 * slightly different background (e.g. completed TODO greyer), pass a
 * custom [alpha].
 *
 * @param onClick when non-null the card becomes tappable with a ripple.
 * @param padding inner padding. Defaults to [KiwiSpacing.lg].
 * @param radius corner radius. Defaults to [KiwiRadii.md].
 * @param alpha background opacity over white. Defaults to
 *   [KiwiOpacity.CARD_BG] (the standard 0.04 wash). Use
 *   [KiwiOpacity.ROW_BG] for list rows that need a bit more contrast.
 */
@Composable
fun KiwiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = KiwiSpacing.lg,
    radius: Dp = KiwiRadii.md,
    alpha: Float = KiwiOpacity.CARD_BG,
    content: @Composable () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
    var m = modifier
        .clip(shape)
        .background(Color.White.copy(alpha = alpha))
    if (onClick != null) {
        m = m.clickable { onClick() }
    }
    m = m.padding(padding)
    androidx.compose.foundation.layout.Box(modifier = m) {
        content()
    }
}
