package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
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
fun UsageStatsScene(scene: Scene.UsageStats) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 56.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(period = scene.period)
            Spacer(Modifier.height(28.dp))
            BigNumbers(scene = scene)
            Spacer(Modifier.height(32.dp))
            TopToolsBlock(tools = scene.topTools)
        }
    }
}

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
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = "Uso de Kiwi",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Light,
                ),
            )
            Text(
                text = labelForPeriod(period),
                color = Color.White.copy(alpha = 0.55f),
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
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.95f),
            style = if (small) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
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
    Spacer(Modifier.height(12.dp))
    if (tools.isEmpty()) {
        Text(
            text = "Sin actividad en este periodo.",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tools) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
