package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.Plan
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)

/**
 * Lista de planes / viajes / eventos especiales del usuario.
 *
 * No es Google Calendar: son cosas que al usuario le hace ilusión ver
 * llegar (viajes, bodas, conciertos). Empujada por los tools
 * ``plan_add`` / ``plan_list`` / ``plan_remove``; también la abre el
 * tap en el chip del Home cuando aparece en un día-milestone.
 */
@Composable
fun PlansListScene(scene: Scene.PlansList) {
    SceneScaffold(
        title = "Planes",
        subtitle = subtitleFor(count = scene.items.size),
    ) {
        if (scene.items.isEmpty()) {
            EmptyState(
                message = "Sin planes apuntados.\nDime: \"apunta mi viaje a Cantabria el viernes\".",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs),
            ) {
                items(scene.items, key = { it.id }) { plan ->
                    PlanRow(plan = plan, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

private fun subtitleFor(count: Int): String = when (count) {
    0 -> "Nada en el horizonte"
    1 -> "1 plan"
    else -> "$count planes"
}

@Composable
private fun PlanRow(plan: Plan, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plan.label,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                overflow = TextOverflow.Visible,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = formatPlanDate(plan.date),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = countdownLabel(plan.daysUntil),
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Etiqueta humana del día restante: "Hoy" / "Mañana" / "En N días". */
internal fun countdownLabel(days: Int): String = when {
    days < 0 -> "Pasó hace ${-days} día${if (-days == 1) "" else "s"}"
    days == 0 -> "Hoy"
    days == 1 -> "Mañana"
    else -> "En $days días"
}

private fun formatPlanDate(iso: String): String {
    return runCatching {
        val d = LocalDate.parse(iso)
        DATE_FORMATTER.format(d).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(SPANISH) else it.toString()
        }
    }.getOrElse { iso }
}
