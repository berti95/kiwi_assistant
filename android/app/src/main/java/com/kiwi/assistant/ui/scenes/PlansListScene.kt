package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale("es", "ES")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
private val SHORT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH)

/**
 * Lista de planes / viajes / eventos especiales del usuario.
 *
 * UI completa (no solo voz):
 *  - "+ Añadir plan" arriba abre un dialog con campo de texto + date
 *    picker para que el usuario pueda apuntar planes a mano sin pasar
 *    por Kiwi.
 *  - cada fila lleva un ✕ que borra el plan del backend al pulsar.
 *  - el flujo por voz (``plan_add`` / ``plan_remove``) sigue
 *    funcionando en paralelo y empuja la misma escena.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansListScene(
    scene: Scene.PlansList,
    onAdd: (label: String, dateIso: String) -> Unit = { _, _ -> },
    onRemove: (id: String) -> Unit = {},
) {
    var showAddDialog by remember { mutableStateOf(false) }

    SceneScaffold(
        title = "Planes",
        subtitle = subtitleFor(count = scene.items.size),
    ) {
        AddPlanButton(onClick = { showAddDialog = true })
        Spacer(Modifier.size(KiwiSpacing.md))

        if (scene.items.isEmpty()) {
            EmptyState(
                message = "Sin planes apuntados.\nToca \"Añadir plan\" o dile a Kiwi: \"apunta mi viaje a Cantabria el viernes\".",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs),
            ) {
                items(scene.items, key = { it.id }) { plan ->
                    PlanRow(
                        plan = plan,
                        onRemove = { onRemove(plan.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPlanDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, dateIso ->
                onAdd(label, dateIso)
                showAddDialog = false
            },
        )
    }
}

private fun subtitleFor(count: Int): String = when (count) {
    0 -> "Nada en el horizonte"
    1 -> "1 plan"
    else -> "$count planes"
}

@Composable
private fun AddPlanButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(Color.White.copy(alpha = KiwiOpacity.CARD_BG))
            .clickable { onClick() }
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        Text(
            text = "Añadir plan",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun PlanRow(
    plan: Plan,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Spacer(Modifier.width(KiwiSpacing.md))
        RemoveButton(onClick = onRemove)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Borrar plan",
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlanDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, dateIso: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var dateIso by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val canSave = label.isNotBlank() && dateIso != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo plan") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Qué") },
                    placeholder = { Text("Viaje a Cantabria") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(KiwiSpacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(KiwiRadii.sm))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { showDatePicker = true }
                        .padding(horizontal = KiwiSpacing.md, vertical = KiwiSpacing.sm + KiwiSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(KiwiSpacing.sm))
                    Text(
                        text = dateIso?.let { formatShortDate(it) } ?: "Elegir fecha",
                        color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { dateIso?.let { onConfirm(label.trim(), it) } },
                enabled = canSave,
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )

    if (showDatePicker) {
        val todayMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = todayMillis,
            // No permitir fechas pasadas; el backend las rechaza igual,
            // pero así el usuario lo ve antes de pulsar Guardar.
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneId.of("UTC")).toLocalDate()
                    return !day.isBefore(LocalDate.now())
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                        dateIso = day.toString()  // ISO YYYY-MM-DD
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun formatShortDate(iso: String): String = runCatching {
    SHORT_DATE_FORMATTER.format(LocalDate.parse(iso))
}.getOrElse { iso }
