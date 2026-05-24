package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
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
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.UsageToolCount
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import kotlin.math.roundToInt

/**
 * Pantalla de costes / uso. Layout en bloques:
 *  - Header con periodo (hoy / 7 días / 30 días).
 *  - Tres números grandes: conversaciones, minutos, coste estimado.
 *  - Lista de tools más usadas (top 8 que devuelve el backend).
 *
 * El coste lleva la palabra "aprox." porque las tarifas están como
 * placeholder en settings — si Google retoca precios o Alberto cambia
 * de modelo, ajustar via env vars.
 */
@Composable
fun UsageStatsScene(
    scene: Scene.UsageStats,
    onSelectPeriod: (String) -> Unit = {},
) {
    SceneScaffold {
        Header(period = scene.period)
        Spacer(Modifier.height(KiwiSpacing.lg))
        PeriodTabs(selected = scene.period, onSelect = onSelectPeriod)
        Spacer(Modifier.height(KiwiSpacing.lg + KiwiSpacing.xs))
        BigNumbers(scene = scene)
        Spacer(Modifier.height(KiwiSpacing.xl))
        TopToolsBlock(tools = scene.topTools)
    }
}

/** Hoy / 7 días / 30 días — toca para recargar ese periodo. */
@Composable
private fun PeriodTabs(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.sm)) {
        for ((period, label) in USAGE_PERIODS) {
            val active = period == selected
            Text(
                text = label,
                color = Color.White.copy(
                    alpha = if (active) 0.95f else KiwiOpacity.TEXT_SECONDARY,
                ),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(KiwiRadii.md))
                    .background(
                        Color.White.copy(
                            alpha = if (active) 0.16f else KiwiOpacity.CARD_BG,
                        ),
                    )
                    .clickable(enabled = !active) { onSelect(period) }
                    .padding(horizontal = KiwiSpacing.lg, vertical = KiwiSpacing.sm + KiwiSpacing.xs),
            )
        }
    }
}

private val USAGE_PERIODS = listOf(
    "today" to "Hoy",
    "7d" to "7 días",
    "30d" to "30 días",
)

@Composable
private fun Header(period: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.QueryStats,
                contentDescription = null,
                tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(KiwiSpacing.lg - 4.dp))
        Column {
            Text(
                text = "Uso de Kiwi",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Light,
                ),
            )
            Text(
                text = labelForPeriod(period),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun labelForPeriod(period: String): String = when (period) {
    "today" -> "Hoy"
    "7d" -> "Últimos 7 días"
    "30d" -> "Últimos 30 días"
    else -> period
}

@Composable
private fun BigNumbers(scene: Scene.UsageStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.md)) {
        StatCard(
            modifier = Modifier.weight(1f),
            value = scene.conversationCount.toString(),
            label = if (scene.conversationCount == 1) "conversación" else "conversaciones",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            value = formatMinutes(scene.audioTotalSeconds),
            label = "audio total",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            value = formatCost(scene.estimatedCostEur),
            label = "coste aprox.",
        )
    }
    Spacer(Modifier.height(KiwiSpacing.sm + KiwiSpacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.md)) {
        StatCard(
            modifier = Modifier.weight(1f),
            value = scene.turnCount.toString(),
            label = "turnos",
            small = true,
        )
        StatCard(
            modifier = Modifier.weight(1f),
            value = formatMinutes(scene.audioInSeconds),
            label = "tú hablaste",
            small = true,
        )
        StatCard(
            modifier = Modifier.weight(1f),
            value = formatMinutes(scene.audioOutSeconds),
            label = "Kiwi habló",
            small = true,
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    small: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KiwiRadii.md))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md + 2.dp),
    ) {
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.95f),
            style = if (small) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(KiwiSpacing.xs))
        Text(
            text = label,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TopToolsBlock(tools: List<UsageToolCount>) {
    Text(
        text = "Tools más usadas",
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
    )
    Spacer(Modifier.height(KiwiSpacing.sm + KiwiSpacing.xs))
    if (tools.isEmpty()) {
        Text(
            text = "Sin actividad en este periodo.",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm)) {
        for (t in tools) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KiwiRadii.sm))
                    .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
                    .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.sm + KiwiSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = t.name,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "× ${t.count}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

private fun formatMinutes(seconds: Double): String {
    if (seconds < 60.0) return "${seconds.roundToInt()} s"
    val minutes = seconds / 60.0
    if (minutes < 10.0) return "%.1f min".format(minutes)
    return "${minutes.roundToInt()} min"
}

private fun formatCost(eur: Double): String {
    if (eur < 0.01) return "<0,01 €"
    return "%.2f €".format(eur)
}
