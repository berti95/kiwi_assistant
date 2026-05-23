"""Tools de TODOs: add, list, complete, remove (todas pintan TodoList)."""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from google.genai import types

from .. import todos
from .registry import ToolResult, register

log = logging.getLogger(__name__)


def _todo_list_scene(items: list[todos.TodoItem]) -> dict[str, Any]:
    """Wire-format scene push for the tablet's TodoList view."""
    return {"type": "todo_list", "items": todos.to_wire(items)}


async def _todo_add(text: str) -> ToolResult:
    """Append a TODO and push the updated list to the tablet."""
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(todos.add, text)
        items = await asyncio.to_thread(todos.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_add failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "added": {"id": item.id, "text": item.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_list() -> ToolResult:
    """Read the user's TODOs and push the list to the tablet."""
    try:
        items = await asyncio.to_thread(todos.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_list failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    pending = [it for it in items if it.completed_ms is None]
    return ToolResult(
        response={
            "count": len(items),
            "pending": len(pending),
            "items": todos.to_wire(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_complete(match: str) -> ToolResult:
    """Mark a TODO as done. Looks up by id or by fuzzy text match."""
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        completed = await asyncio.to_thread(todos.complete, match)
        items = await asyncio.to_thread(todos.list_all)
    except todos.TodoNotFoundError:
        # Re-read so the LLM can tell the user what's actually pending.
        try:
            items = await asyncio.to_thread(todos.list_all)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no todo matched {match!r}. pending: "
                    f"{[it.text for it in items if it.completed_ms is None]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_complete failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "completed": {"id": completed.id, "text": completed.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_remove(match: str) -> ToolResult:
    """Delete a TODO. Looks up by id or by fuzzy text match."""
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(todos.remove, match)
        items = await asyncio.to_thread(todos.list_all)
    except todos.TodoNotFoundError:
        try:
            items = await asyncio.to_thread(todos.list_all)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no todo matched {match!r}. existing: "
                    f"{[it.text for it in items]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_remove failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "removed": {"id": removed.id, "text": removed.text},
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


register(
    name="todo_add",
    description=(
        "Apunta una tarea o recordatorio en la lista de pendientes del "
        "usuario. Llama a este tool cuando el usuario diga 'apúntame', "
        "'recuérdame', 'añade a la lista', 'tengo que', 'no se me olvide' "
        "o variantes similares. La pantalla del tablet se actualiza con "
        "la lista completa; tu respuesta hablada confirma brevemente lo "
        "que apuntaste."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "text": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Texto LITERAL de la tarea, lo más fiel posible a lo "
                    "que dijo el usuario, sin resumir ni reformular. Si "
                    "añadió motivo / contexto / plazo / descripción larga, "
                    "inclúyelo entero — el desarrollador lee la lista para "
                    "implementarla. Captura completa > captura corta."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_todo_add,
)

register(
    name="todo_list",
    description=(
        "Devuelve y muestra la lista completa de pendientes del usuario. "
        "Llama a este tool cuando pregunte 'qué tengo pendiente', 'qué "
        "tengo que hacer', 'cuáles son mis tareas', 'enséñame la lista', "
        "etc. La pantalla muestra el detalle; tu voz resume cuántas hay "
        "y, si son pocas, las menciona."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_todo_list,
)

register(
    name="todo_complete",
    description=(
        "Marca una tarea pendiente como completada. Llama a este tool "
        "cuando el usuario diga 'ya hice X', 'tacha X', 'márcame X "
        "como hecho' o similar. Pasa en 'match' un trozo del texto de "
        "la tarea (o su id si lo conoces de un tool anterior); el "
        "backend hace coincidencia parcial sin distinguir mayúsculas "
        "ni acentos. Si no encuentra una coincidencia única, devuelve "
        "un error con la lista actual para que pidas al usuario que "
        "concrete."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del texto de la tarea (o su id). P.ej. "
                    "'tomates' para 'comprar tomates'."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_todo_complete,
)

register(
    name="todo_remove",
    description=(
        "Borra una tarea de la lista. Llama a este tool cuando el "
        "usuario diga 'borra X', 'quita X de la lista', 'elimina X' "
        "o similar. Mismo sistema de matching que todo_complete: "
        "pasa un trozo del texto o el id. Si la diferencia entre 'ya "
        "lo hice' y 'bórralo' no está clara en la conversación, "
        "prefiere todo_complete (deja rastro de la tarea hecha)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del texto de la tarea (o su id) a borrar."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_todo_remove,
)
