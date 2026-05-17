package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.TodoItem
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

/**
 * Renders the user's TODO list.
 *
 * Pending items at the top, completed items below (greyed out and
 * struck through). Tap on a pending item marks it done; tap again on
 * a completed item removes it. Voice flow keeps working in parallel
 * (todo_complete / todo_remove tools).
 */
@Composable
fun TodoListScene(
    scene: Scene.TodoList,
    onTodoTap: (TodoItem) -> Unit,
) {
    val pending = scene.items.filter { !it.completed }
    val done = scene.items.filter { it.completed }

    SceneScaffold(
        title = "Pendientes",
        subtitle = subtitleFor(pending = pending.size, total = scene.items.size),
    ) {
        HelperHint(hasDone = done.isNotEmpty(), hasPending = pending.isNotEmpty())
        Spacer(Modifier.size(KiwiSpacing.md))

        if (scene.items.isEmpty()) {
            EmptyState(message = "Nada apuntado.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
                itemsIndexed(pending, key = { _, it -> it.id }) { index, item ->
                    TodoRow(
                        position = index + 1,
                        item = item,
                        onTap = onTodoTap,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (done.isNotEmpty()) {
                    item(key = "completed-header") {
                        CompletedSectionHeader(
                            count = done.size,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    itemsIndexed(done, key = { _, it -> it.id }) { _, item ->
                        TodoRow(
                            position = null,
                            item = item,
                            onTap = onTodoTap,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelperHint(hasDone: Boolean, hasPending: Boolean) {
    val text = when {
        hasPending && hasDone -> "Toca para marcar como hecho · toca de nuevo para borrar"
        hasPending -> "Toca cualquier tarea para marcarla como hecha"
        hasDone -> "Toca una tarea hecha para borrarla"
        else -> ""
    }
    if (text.isBlank()) return
    Text(
        text = text,
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun subtitleFor(pending: Int, total: Int): String {
    if (total == 0) return "Sin tareas"
    if (pending == total) {
        return if (total == 1) "1 tarea" else "$total tareas"
    }
    val noun = if (pending == 1) "pendiente" else "pendientes"
    return "$pending $noun · $total en total"
}

@Composable
private fun CompletedSectionHeader(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = if (count == 1) "Hecho" else "Hechas",
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = modifier.padding(top = KiwiSpacing.lg, bottom = KiwiSpacing.xs),
    )
}

@Composable
private fun TodoRow(
    position: Int?,
    item: TodoItem,
    onTap: (TodoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm + 4.dp))
            .background(
                Color.White.copy(
                    alpha = if (item.completed) 0.03f else KiwiOpacity.ROW_BG,
                ),
            )
            .clickable { onTap(item) }
            .padding(horizontal = KiwiSpacing.lg - 4.dp, vertical = KiwiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            PositionBadge(position)
            Spacer(Modifier.width(KiwiSpacing.md))
        } else {
            Spacer(Modifier.width(36.dp + KiwiSpacing.md))
        }
        Text(
            text = item.text,
            color = Color.White.copy(
                alpha = if (item.completed) KiwiOpacity.TEXT_TERTIARY else 0.95f,
            ),
            style = MaterialTheme.typography.titleMedium.copy(
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
            ),
            // No max — let long capture-style entries (multi-clause
            // ideas, full-sentence reminders) wrap as far as needed.
            // The list is a LazyColumn so vertical room is unlimited.
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
        StatusBadge(completed = item.completed)
    }
}

/**
 * Numbered chip on the left of pending rows so the user can match
 * what they see ("la dos") with what they say. Mirror of the badge
 * used in [VideoListScene] so the visual idiom is consistent.
 */
@Composable
private fun PositionBadge(position: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = position.toString(),
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
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
