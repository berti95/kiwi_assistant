package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.TodoOwner
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import com.kiwi.assistant.ui.theme.todoDueToday
import com.kiwi.assistant.ui.theme.todoKiwiAccent
import com.kiwi.assistant.ui.theme.todoMineAccent
import com.kiwi.assistant.ui.theme.todoOverdue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pantalla de pendientes.
 *
 * Dos secciones: **Para mí** (tareas del usuario, con fecha opcional
 * y badge rojo / ámbar para vencidas / hoy) y **Para Kiwi** (encargos
 * para la IA, sin fecha — el usuario los recolecta y dice "revisa la
 * lista"). Hechas al final, agrupadas en una sola sección común.
 *
 * Tap en pendiente → marcar hecho. Tap en hecho → eliminar. Botón "+"
 * arriba abre un diálogo con texto + sección + (si es propia) fecha.
 */
@Composable
fun TodoListScene(
    scene: Scene.TodoList,
    onTodoTap: (TodoItem) -> Unit,
    onAddTodo: (String, TodoOwner, String?) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }

    SceneScaffold(
        title = "Pendientes",
        subtitle = subtitleFor(scene.items),
        background = Brush.verticalGradient(
            colors = listOf(Color(0xFF0B1118), Color.Black),
        ),
    ) {
        TodoContent(
            items = scene.items,
            onTodoTap = onTodoTap,
            onAddClick = { showAdd = true },
        )
    }

    if (showAdd) {
        AddTodoDialog(
            onDismiss = { showAdd = false },
            onConfirm = { text, owner, due ->
                onAddTodo(text, owner, due)
                showAdd = false
            },
        )
    }
}

@Composable
private fun TodoContent(
    items: List<TodoItem>,
    onTodoTap: (TodoItem) -> Unit,
    onAddClick: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val pending = items.filter { !it.completed }
    val done = items.filter { it.completed }
    val mine = pending.filter { it.owner == TodoOwner.Mine }
        .sortedWith(MineComparator(today))
    val kiwi = pending.filter { it.owner == TodoOwner.Kiwi }

    AddRow(onAddClick = onAddClick)
    Spacer(Modifier.size(KiwiSpacing.md))

    if (items.isEmpty()) {
        EmptyState(message = "Nada apuntado. Toca + para empezar.")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
        if (mine.isNotEmpty()) {
            item(key = "header-mine") {
                SectionHeader(
                    label = "Para mí",
                    count = mine.size,
                    accent = MaterialTheme.colorScheme.todoMineAccent,
                    icon = Icons.Filled.Person,
                    modifier = Modifier.animateItem(),
                )
            }
            items(mine, key = { it.id }) { item ->
                TodoRow(
                    item = item,
                    today = today,
                    accent = MaterialTheme.colorScheme.todoMineAccent,
                    onTap = onTodoTap,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (kiwi.isNotEmpty()) {
            item(key = "header-kiwi") {
                SectionHeader(
                    label = "Para Kiwi",
                    count = kiwi.size,
                    accent = MaterialTheme.colorScheme.todoKiwiAccent,
                    icon = Icons.Filled.SmartToy,
                    subtitle = if (kiwi.size == 1) "Lo hará cuando le digas \"revisa la lista\""
                               else "Los hará cuando le digas \"revisa la lista\"",
                    modifier = Modifier.animateItem(),
                )
            }
            items(kiwi, key = { it.id }) { item ->
                TodoRow(
                    item = item,
                    today = today,
                    accent = MaterialTheme.colorScheme.todoKiwiAccent,
                    onTap = onTodoTap,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (done.isNotEmpty()) {
            item(key = "header-done") {
                SectionHeader(
                    label = if (done.size == 1) "Hecho" else "Hechas",
                    count = done.size,
                    accent = Color.White.copy(alpha = 0.25f),
                    icon = Icons.Filled.Check,
                    modifier = Modifier.animateItem(),
                )
            }
            items(done, key = { it.id }) { item ->
                TodoRow(
                    item = item,
                    today = today,
                    accent = Color.White.copy(alpha = 0.20f),
                    onTap = onTodoTap,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private fun subtitleFor(items: List<TodoItem>): String {
    if (items.isEmpty()) return "Sin tareas"
    val pending = items.count { !it.completed }
    if (pending == items.size) {
        return if (pending == 1) "1 tarea" else "$pending tareas"
    }
    val noun = if (pending == 1) "pendiente" else "pendientes"
    return "$pending $noun · ${items.size} en total"
}

@Composable
private fun AddRow(onAddClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(Color.White.copy(alpha = KiwiOpacity.ROW_BG))
            .clickable(onClick = onAddClick)
            .padding(horizontal = KiwiSpacing.lg, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.todoMineAccent.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.todoMineAccent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(KiwiSpacing.md))
        Text(
            text = "Añadir tarea",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.padding(top = KiwiSpacing.md, bottom = KiwiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        Text(
            text = label,
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.width(KiwiSpacing.sm))
        Text(
            text = "· $count",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
            style = MaterialTheme.typography.titleMedium,
        )
        if (subtitle != null) {
            Spacer(Modifier.width(KiwiSpacing.md))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    today: LocalDate,
    accent: Color,
    onTap: (TodoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val due = dueStateOf(item.dueDate, today)
    val isOverdue = due is DueState.Overdue && !item.completed
    val bgColor = if (item.completed) Color.White.copy(alpha = 0.03f)
                  else Color.White.copy(alpha = KiwiOpacity.ROW_BG)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(bgColor)
            .let { m ->
                if (isOverdue) m.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.todoOverdue.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(KiwiRadii.sm + 4.dp),
                ) else m
            }
            .clickable { onTap(item) }
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Banda de color a la izquierda según la sección.
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = if (item.completed) 0.20f else 0.70f)),
        )
        Spacer(Modifier.width(KiwiSpacing.md))
        Text(
            text = item.text,
            color = Color.White.copy(
                alpha = if (item.completed) KiwiOpacity.TEXT_TERTIARY else 0.95f,
            ),
            style = MaterialTheme.typography.titleMedium.copy(
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
            ),
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
        )
        if (due !is DueState.None && !item.completed) {
            Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
            DueBadge(state = due)
        }
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        StatusBadge(completed = item.completed)
    }
}

@Composable
private fun DueBadge(state: DueState) {
    val (bg, fg, label) = when (state) {
        is DueState.Overdue -> Triple(
            MaterialTheme.colorScheme.todoOverdue,
            Color.White,
            overdueLabel(state.daysAgo),
        )
        DueState.Today -> Triple(
            MaterialTheme.colorScheme.todoDueToday,
            Color.Black.copy(alpha = 0.78f),
            "Hoy",
        )
        is DueState.Future -> Triple(
            Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.85f),
            futureLabel(state.daysAhead, state.date),
        )
        DueState.None -> Triple(Color.Transparent, Color.Transparent, "")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = KiwiSpacing.md - 4.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

private fun overdueLabel(daysAgo: Long): String = when (daysAgo) {
    1L -> "Ayer"
    in 2..6 -> "Hace ${daysAgo}d"
    else -> "Vencida"
}

private fun futureLabel(daysAhead: Long, date: LocalDate): String = when (daysAhead) {
    1L -> "Mañana"
    in 2..6 -> {
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
        dow.replaceFirstChar { it.uppercase() }
    }
    else -> "${date.dayOfMonth} ${
        date.month.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
    }"
}

@Composable
private fun StatusBadge(completed: Boolean) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (completed) Color.White.copy(alpha = 0.12f) else Color.Transparent,
            )
            .border(
                width = 1.5.dp,
                color = Color.White.copy(
                    alpha = if (completed) 0.4f else KiwiOpacity.ICON_DIM,
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (completed) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Date model / comparator -----------------------------------------

private sealed interface DueState {
    data class Overdue(val daysAgo: Long, val date: LocalDate) : DueState
    data object Today : DueState
    data class Future(val daysAhead: Long, val date: LocalDate) : DueState
    data object None : DueState
}

private fun dueStateOf(isoDate: String?, today: LocalDate): DueState {
    if (isoDate.isNullOrBlank()) return DueState.None
    val due = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return DueState.None
    val days = ChronoUnit.DAYS.between(today, due)
    return when {
        days < 0 -> DueState.Overdue(daysAgo = -days, date = due)
        days == 0L -> DueState.Today
        else -> DueState.Future(daysAhead = days, date = due)
    }
}

/**
 * Orden dentro de "Para mí": vencidas primero (más antigua arriba),
 * luego hoy, luego futuras por proximidad, y las sin fecha al fondo.
 */
private class MineComparator(private val today: LocalDate) : Comparator<TodoItem> {
    override fun compare(a: TodoItem, b: TodoItem): Int {
        return bucketKey(a).compareTo(bucketKey(b))
    }
    private fun bucketKey(item: TodoItem): Long {
        val state = dueStateOf(item.dueDate, today)
        return when (state) {
            is DueState.Overdue -> -1_000_000L - state.daysAgo  // más antigua = clave menor
            DueState.Today -> 0L
            is DueState.Future -> state.daysAhead
            DueState.None -> Long.MAX_VALUE
        }
    }
}

// ---- Add dialog -------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, owner: TodoOwner, dueDate: String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(TodoOwner.Mine) }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir tarea") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Qué hay que hacer") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(KiwiSpacing.md))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = owner == TodoOwner.Mine,
                        onClick = { owner = TodoOwner.Mine },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) { Text("Para mí") }
                    SegmentedButton(
                        selected = owner == TodoOwner.Kiwi,
                        onClick = {
                            owner = TodoOwner.Kiwi
                            due = null  // Kiwi-owned no llevan fecha
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                Icons.Filled.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) { Text("Para Kiwi") }
                }
                if (owner == TodoOwner.Mine) {
                    Spacer(Modifier.size(KiwiSpacing.md))
                    AssistChip(
                        onClick = { showPicker = true },
                        label = {
                            Text(due?.let { formatDateForChip(it) } ?: "Sin fecha")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.todoMineAccent
                                .copy(alpha = 0.18f),
                            labelColor = Color.White,
                            leadingIconContentColor = MaterialTheme.colorScheme.todoMineAccent,
                        ),
                    )
                    if (due != null) {
                        TextButton(onClick = { due = null }) {
                            Text("Quitar fecha")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        text.trim(),
                        owner,
                        due?.toString(),  // ISO YYYY-MM-DD
                    )
                },
            ) { Text("Añadir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )

    if (showPicker) {
        val initial = due?.atStartOfDay(ZoneId.systemDefault())
            ?.toInstant()?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        due = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    }
                    showPicker = false
                }) { Text("Elegir") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun formatDateForChip(date: LocalDate): String {
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(today, date)
    return when (days) {
        0L -> "Hoy"
        1L -> "Mañana"
        in 2..6 -> {
            val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
            dow.replaceFirstChar { it.uppercase() }
        }
        else -> "${date.dayOfMonth} ${
            date.month.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
        }"
    }
}
