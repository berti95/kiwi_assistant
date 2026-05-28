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


async def _todo_add(
    text: str,
    owner: str = todos.DEFAULT_OWNER,
    due_date: str | None = None,
) -> ToolResult:
    """Append a TODO and push the updated list to the tablet.

    ``owner`` controla en qué sección cae:
    - ``"mine"`` (default): tarea del usuario, puede llevar fecha.
    - ``"kiwi"``: encargo para la IA — ignora ``due_date``.
    """
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(
            todos.add, text, owner=owner, due_date=due_date,
        )
        items = await asyncio.to_thread(todos.list_all)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_add failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "added": {
                "id": item.id,
                "text": item.text,
                "owner": item.owner,
                "due_date": item.due_date,
            },
            "count": len(items),
        },
        scene=_todo_list_scene(items),
    )


async def _todo_list_for_kiwi() -> ToolResult:
    """Devuelve los encargos pendientes para la IA (owner=kiwi).

    Llamado cuando el usuario dice "revisa la lista", "mira los recados
    que te dejé" o equivalente. La respuesta es texto plano para que el
    modelo lo procese como instrucciones independientes; no
    actualizamos la escena (el usuario suele estar pidiéndolas para
    que las EJECUTES, no para verlas en pantalla).
    """
    try:
        items = await asyncio.to_thread(todos.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("todo_list_for_kiwi failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    pending_kiwi = [
        {"id": it.id, "text": it.text}
        for it in items
        if it.owner == "kiwi" and it.completed_ms is None
    ]
    return ToolResult(
        response={
            "count": len(pending_kiwi),
            "items": pending_kiwi,
        },
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
        "que apuntaste.\n\n"
        "Hay dos clases de tarea según quién la vaya a hacer:\n"
        "- owner='mine' (por defecto): la hará el usuario. Si menciona "
        "  una fecha ('para el lunes', 'mañana', 'el día 15') resuelve "
        "  la fecha en ISO YYYY-MM-DD y pásala en due_date. Para 'hoy', "
        "  usa la fecha de hoy.\n"
        "- owner='kiwi': encargo para ti (la IA), cosas que el usuario "
        "  quiere que TÚ hagas más tarde ('apunta para que tú me busques "
        "  X', 'añade para que mires Y', 'recuérdate que tienes que…'). "
        "  Ignora due_date en este caso."
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
            "owner": types.Schema(
                type=types.Type.STRING,
                enum=["mine", "kiwi"],
                description=(
                    "'mine' (default) si la hace el usuario; 'kiwi' si es "
                    "un encargo para ti que ejecutarás luego cuando el "
                    "usuario diga 'revisa la lista'."
                ),
            ),
            "due_date": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Fecha límite ISO YYYY-MM-DD (sólo con owner='mine'). "
                    "Resuelve expresiones relativas ('mañana', 'el lunes') "
                    "respecto a la fecha de hoy. Omite si no hay fecha."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_todo_add,
)

register(
    name="todo_list_for_kiwi",
    description=(
        "Devuelve los encargos que el usuario te ha dejado a TI (las "
        "tareas con owner='kiwi'). Llama a este tool cuando el usuario "
        "diga 'revisa la lista', 'mira los recados que te dejé', 'haz "
        "las cosas que te apunté', 'revisa lo tuyo' o similar. Una vez "
        "que tengas los items, procesa cada uno como una instrucción "
        "independiente — busca info, calcula, llama a otros tools, etc. "
        "Cuando termines con uno, marca esa tarea como completada con "
        "todo_complete pasando su id."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_todo_list_for_kiwi,
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
