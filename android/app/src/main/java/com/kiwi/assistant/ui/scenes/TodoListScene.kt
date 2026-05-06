package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * Renders the user's TODO list.
 *
 * Pending items at the top, completed items below (greyed out and
 * struck through). All operations happen via voice — there are no
 * tap targets.
 */
@Composable
fun TodoListScene(scene: Scene.TodoList) {
    val pending = scene.items.filter { !it.completed }
    val done = scene.items.filter { it.completed }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 56.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(pending = pending.size, total = scene.items.size)
            Spacer(Modifier.height(24.dp))

            if (scene.items.isEmpty()) {
                EmptyTodos()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(pending, key = { _, it -> it.id }) { index, item ->
                        TodoRow(position = index + 1, item = item)
                    }
                    if (done.isNotEmpty()) {
                        item(key = "completed-header") {
                            CompletedSectionHeader(count = done.size)
                        }
                        itemsIndexed(done, key = { _, it -> it.id }) { _, item ->
                            TodoRow(position = null, item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(pending: Int, total: Int) {
    Column {
        Text(
            text = "Pendientes",
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
            ),
        )
        Text(
            text = subtitleFor(pending = pending, total = total),
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.titleMedium,
        )
    }
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
private fun CompletedSectionHeader(count: Int) {
    Text(
        text = if (count == 1) "Hecho" else "Hechas",
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyTodos() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nada apuntado.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun TodoRow(position: Int?, item: TodoItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (item.completed) 0.03f else 0.06f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            PositionBadge(position)
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(36.dp + 16.dp))
        }
        Text(
            text = item.text,
            color = Color.White.copy(alpha = if (item.completed) 0.45f else 0.95f),
            style = MaterialTheme.typography.titleMedium.copy(
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
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
            color = Color.White.copy(alpha = 0.92f),
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
                color = Color.White.copy(alpha = if (completed) 0.4f else 0.45f),
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
