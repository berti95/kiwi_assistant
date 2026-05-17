package com.kiwi.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Common shell for every scene: black background, standard padding,
 * and an optional top bar with back arrow + title + close-to-home X.
 *
 * Scenes that opt in stop worrying about padding values and chrome —
 * they just declare what their inner content is.
 *
 * @param title shown in the top bar. If null, no top bar is rendered.
 * @param onBack invoked when the back arrow is tapped. Hidden if null.
 * @param onClose invoked when the X is tapped. Hidden if null.
 * @param subtitle optional secondary line under the title (e.g.
 *   "3 pendientes · 5 en total").
 * @param horizontalPadding outer horizontal padding. Most scenes use
 *   [KiwiSpacing.xxl] (48dp) on a 10" tablet.
 * @param verticalPadding outer vertical padding.
 */
@Composable
fun SceneScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = KiwiSpacing.xxl,
    verticalPadding: androidx.compose.ui.unit.Dp = KiwiSpacing.huge,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (title != null || onBack != null || onClose != null) {
                SceneTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    onClose = onClose,
                )
                Spacer(Modifier.height(KiwiSpacing.lg))
            }
            content()
        }
    }
}

@Composable
private fun SceneTopBar(
    title: String?,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircularIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                onClick = onBack,
            )
            Spacer(Modifier.width(KiwiSpacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                    ),
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (onClose != null) {
            CircularIconButton(
                icon = Icons.Default.Close,
                contentDescription = "Cerrar",
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun CircularIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = KiwiOpacity.BADGE_BG))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            modifier = Modifier.size(24.dp),
        )
    }
}
