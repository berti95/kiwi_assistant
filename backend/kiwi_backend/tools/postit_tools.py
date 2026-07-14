"""Tools de voz para post-its: add / list / remove.

Post-its son notas rápidas ancladas al home ("chuleta wifi",
"llaves del vecino"). No tienen ciclo de vida (no se "completan"
como un TODO), sólo se ponen y se quitan.
"""
from __future__ import annotations

import asyncio
import logging

from google.genai import types

from .. import postits
from .registry import ToolResult, register

log = logging.getLogger(__name__)


async def _postit_add(text: str, color: str = "yellow") -> ToolResult:
    """Añade un post-it al home. Color inválido cae a "yellow" en el módulo."""
    if not text or not text.strip():
        return ToolResult(response={"error": "missing text"})
    try:
        item = await asyncio.to_thread(postits.add, text, color)
        items = await asyncio.to_thread(postits.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("postit_add failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    return ToolResult(
        response={
            "added": {"id": item.id, "text": item.text, "color": item.color},
            "count": len(items),
        },
    )


async def _postit_list() -> ToolResult:
    try:
        items = await asyncio.to_thread(postits.list_all)
    except Exception as exc:  # noqa: BLE001
        log.exception("postit_list failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    return ToolResult(
        response={
            "count": len(items),
            "items": postits.to_wire(items),
        },
    )


async def _postit_remove(match: str) -> ToolResult:
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(postits.remove, match)
        items = await asyncio.to_thread(postits.list_all)
    except postits.PostItNotFoundError:
        try:
            items = await asyncio.to_thread(postits.list_all)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no postit matched {match!r}. existing: "
                    f"{[it.text for it in items]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("postit_remove failed")
        return ToolResult(response={"error": f"{type(exc).__name__}: {exc}"})
    return ToolResult(
        response={
            "removed": {"id": removed.id, "text": removed.text},
            "count": len(items),
        },
    )


register(
    name="postit_add",
    description=(
        "Pega un post-it en el home del tablet. Notas rápidas y "
        "efímeras — nombres, códigos, chuletas, avisos cortos. "
        "Llama a este tool cuando el usuario diga 'pon un post-it', "
        "'apunta en la pared', 'déjame una nota que diga' o "
        "similar. Si dice un color explícito (\"post-it verde\", "
        "\"amarillo\"), pásalo en `color`; si no, omite el "
        "parámetro para que use el amarillo por defecto. Los "
        "colores válidos son: yellow, pink, blue, green, orange, "
        "purple. Para tareas con seguimiento usa todo_add en su "
        "lugar."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "text": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Texto del post-it, corto y literal (máx. 140 "
                    "caracteres). Ideal para memos: 'wifi vecino "
                    "PISO_ABAJO/12345', 'llaves en el cajón azul'."
                ),
            ),
            "color": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Color del post-it. Valores válidos: yellow, "
                    "pink, blue, green, orange, purple. Omite si el "
                    "usuario no lo especifica."
                ),
            ),
        },
        required=["text"],
    ),
    handler=_postit_add,
)

register(
    name="postit_list",
    description=(
        "Lee los post-its actuales del home. Útil si el usuario "
        "pregunta 'qué post-its tengo puestos', 'léeme la pared', "
        "'qué notas hay'."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_postit_list,
)

register(
    name="postit_remove",
    description=(
        "Quita un post-it del home. Llama a este tool cuando el "
        "usuario diga 'quita el post-it de X', 'ya no necesito la "
        "nota de X', 'borra el amarillo que pone X'. Matching por "
        "id o por fragmento de texto (case + acentos insensitivos)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo del texto del post-it (o su id) a "
                    "borrar."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_postit_remove,
)
