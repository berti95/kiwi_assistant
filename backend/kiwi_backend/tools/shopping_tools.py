"""Tools de lista de la compra (separadas de TODOs)."""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from google.genai import types

from .. import shopping
from .registry import ToolResult, register

log = logging.getLogger(__name__)


def _shopping_list_scene(items: list[shopping.ShoppingItem]) -> dict[str, Any]:
    return {"type": "shopping_list", "items": shopping.to_wire(items)}


async def _shopping_add(text: str) -> ToolResult:
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(shopping.add, text)
        items = await asyncio.to_thread(shopping.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("shopping_add failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    return ToolResult(
        response={
            "added": {"id": item.id, "text": item.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_list() -> ToolResult:
    items = await asyncio.to_thread(shopping.list_all)
    pending = [it for it in items if it.completed_ms is None]
    return ToolResult(
        response={
            "count": len(items),
            "pending": len(pending),
            "items": shopping.to_wire(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_complete(match: str) -> ToolResult:
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        completed = await asyncio.to_thread(shopping.complete, match)
        items = await asyncio.to_thread(shopping.list_all)
    except shopping.ShoppingItemNotFoundError:
        items = await asyncio.to_thread(shopping.list_all)
        return ToolResult(response={
            "error": (
                f"no hay nada en la compra que coincida con {match!r}. "
                f"Pendientes: {[it.text for it in items if it.completed_ms is None]}"
            ),
        })
    return ToolResult(
        response={
            "completed": {"id": completed.id, "text": completed.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_remove(match: str) -> ToolResult:
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(shopping.remove, match)
        items = await asyncio.to_thread(shopping.list_all)
    except shopping.ShoppingItemNotFoundError:
        items = await asyncio.to_thread(shopping.list_all)
        return ToolResult(response={
            "error": (
                f"no hay {match!r} en la compra. Existing: "
                f"{[it.text for it in items]}"
            ),
        })
    return ToolResult(
        response={
            "removed": {"id": removed.id, "text": removed.text},
            "count": len(items),
        },
        scene=_shopping_list_scene(items),
    )


async def _shopping_clear() -> ToolResult:
    """'Ya he hecho la compra' — vacía la lista entera."""
    cleared = await asyncio.to_thread(shopping.clear_all)
    return ToolResult(
        response={"cleared": cleared},
        scene=_shopping_list_scene([]),
    )


register(
    name="shopping_add",
    description=(
        "Añade un artículo a la lista de la compra. Llama cuando el "
        "usuario diga 'añade a la compra', 'apunta para comprar', "
        "'me hace falta X', 'cómprame X', etc. Pasa en `text` lo más "
        "literal que dijo (incluye cantidad si la mencionó: '2 litros "
        "de leche', 'un kilo de tomates'). NO confundir con todo_add: "
        "los TODOs son tareas/ideas; esto va al carro de la compra."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "text": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Artículo a añadir, con cantidad si la dijo el "
                    "usuario. Ej: 'leche', '2 litros de leche', "
                    "'pan integral'."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_shopping_add,
)

register(
    name="shopping_list",
    description=(
        "Devuelve y muestra la lista de la compra. Llama cuando el "
        "usuario pregunte 'qué tengo en la compra', 'qué hay que "
        "comprar', 'enséñame la lista de la compra', etc."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_shopping_list,
)

register(
    name="shopping_complete",
    description=(
        "Marca un artículo como ya comprado / metido en el carro. "
        "Llama cuando el usuario diga 'ya tengo X', 'tacha X', 'X "
        "ya lo cogí', 'metí X', etc. Coincidencia parcial sin acentos "
        "en `match`."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Trozo del nombre del artículo (o id).",
            ),
        },
        required=["match"],
    ),
    handler=_shopping_complete,
)

register(
    name="shopping_remove",
    description=(
        "Borra un artículo de la lista (no lo marca, lo elimina). "
        "Llama cuando el usuario diga 'quita X de la compra', 'borra "
        "X', 'al final no compres X', etc."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Trozo del nombre del artículo (o id) a borrar.",
            ),
        },
        required=["match"],
    ),
    handler=_shopping_remove,
)

register(
    name="shopping_clear",
    description=(
        "Vacía la lista de la compra entera. Llama SOLO cuando el "
        "usuario diga claramente 'ya he hecho la compra', 'vacía la "
        "compra', 'reinicia la lista', etc. — es destructivo, no se "
        "deshace."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_shopping_clear,
)
