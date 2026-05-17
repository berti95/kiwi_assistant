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
import com.kiwi.assistant.ui.components.EmptyState
import com.kiwi.assistant.ui.components.SceneScaffold
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing

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

    SceneScaffold {
        Header(pending = pending.size, total = scene.items.size)
        Spacer(Modifier.size(KiwiSpacing.sm))
        HelperHint(hasDone = done.isNotEmpty(), hasPending = pending.isNotEmpty())
        Spacer(Modifier.size(KiwiSpacing.md))
        if (scene.items.isEmpty()) {
            EmptyState(message = "Nada en la lista de la compra.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.sm + KiwiSpacing.xs)) {
                itemsIndexed(pending, key = { _, it -> it.id }) { index, item ->
                    ItemRow(
                        position = index + 1,
                        item = item,
                        onTap = onItemTap,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (done.isNotEmpty()) {
                    item(key = "done-header") {
                        DoneHeader(count = done.size, modifier = Modifier.animateItem())
                    }
                    itemsIndexed(done, key = { _, it -> it.id }) { _, item ->
                        ItemRow(
                            position = null,
                            item = item,
                            onTap = onItemTap,
                            modifier = Modifier.animateItem(),
                        )
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
                tint = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(KiwiSpacing.lg - 4.dp))
        Column {
            Text(
                text = "La compra",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_PRIMARY),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Light,
                ),
            )
            Text(
                text = subtitleFor(pending = pending, total = total),
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
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
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DoneHeader(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = if (count == 1) "En el carro" else "En el carro ($count)",
        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = modifier.padding(top = KiwiSpacing.lg, bottom = KiwiSpacing.xs),
    )
}

@Composable
private fun ItemRow(
    position: Int?,
    item: ShoppingItem,
    onTap: (ShoppingItem) -> Unit,
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
            maxLines = 3,
            overflow = TextOverflow.Visible,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KiwiSpacing.sm + KiwiSpacing.xs))
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
