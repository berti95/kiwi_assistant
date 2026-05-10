package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ShoppingCart
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
import com.kiwi.assistant.ui.ShoppingItem

/**
 * Lista de la compra con tap-to-toggle.
 *
 * Espejo de [TodoListScene] pero con icono de carrito en la cabecera
 * y el matiz semántico de "completado = ya comprado, en el carro" en
 * lugar de "tarea hecha". Tap en pendiente lo tacha; tap en tachado
 * lo elimina (mismo gesto, mismo wire format /api/shopping/...).
 */
@Composable
fun ShoppingListScene(
    scene: Scene.ShoppingList,
    onItemTap: (ShoppingItem) -> Unit,
) {
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
            Spacer(Modifier.height(8.dp))
            HelperHint(hasDone = done.isNotEmpty(), hasPending = pending.isNotEmpty())
            Spacer(Modifier.height(16.dp))
            if (scene.items.isEmpty()) {
                Empty()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(pending, key = { _, it -> it.id }) { index, item ->
                        ItemRow(position = index + 1, item = item, onTap = onItemTap)
                    }
                    if (done.isNotEmpty()) {
                        item(key = "done-header") { DoneHeader(count = done.size) }
                        itemsIndexed(done, key = { _, it -> it.id }) { _, item ->
                            ItemRow(position = null, item = item, onTap = onItemTap)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(pending: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ShoppingCart,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = "La compra",
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
}

private fun subtitleFor(pending: Int, total: Int): String {
    if (total == 0) return "Lista vacía"
    if (pending == total) {
        return if (total == 1) "1 cosa por comprar" else "$total cosas por comprar"
    }
    val noun = if (pending == 1) "pendiente" else "pendientes"
    return "$pending $noun · $total en total"
}

@Composable
private fun HelperHint(hasDone: Boolean, hasPending: Boolean) {
    val text = when {
        hasPending && hasDone -> "Toca para marcarlo como cogido · toca de nuevo para borrarlo"
        hasPending -> "Toca un artículo cuando lo metas en el carro"
        hasDone -> "Toca un artículo ya cogido para borrarlo"
        else -> ""
    }
    if (text.isBlank()) return
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.4f),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DoneHeader(count: Int) {
    Text(
        text = if (count == 1) "En el carro" else "En el carro ($count)",
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nada en la lista de la compra.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun ItemRow(
    position: Int?,
    item: ShoppingItem,
    onTap: (ShoppingItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (item.completed) 0.03f else 0.06f))
            .clickable { onTap(item) }
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
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        StatusBadge(completed = item.completed)
    }
}

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
