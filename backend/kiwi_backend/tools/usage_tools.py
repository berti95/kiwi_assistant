"""Tool de estadísticas de uso (conversaciones, audio, coste)."""
from __future__ import annotations

import asyncio

from google.genai import types

from .. import usage
from .registry import ToolResult, register

_VALID_USAGE_PERIODS = {"today", "7d", "30d"}


async def _usage_stats(period: str = "today") -> ToolResult:
    """Devuelve y muestra estadísticas de uso para un periodo."""
    if period not in _VALID_USAGE_PERIODS:
        return ToolResult(response={
            "error": (
                f"period inválido {period!r}. valores: "
                f"{sorted(_VALID_USAGE_PERIODS)}"
            ),
        })
    payload = await asyncio.to_thread(usage.aggregate, period)
    return ToolResult(
        response=payload,
        scene={"type": "usage_stats", **payload},
    )


register(
    name="usage_stats",
    description=(
        "Devuelve y muestra cuánto se ha usado Kiwi en un periodo: "
        "número de conversaciones, minutos de audio, top de tools "
        "usadas y coste estimado en EUR. Llama a este tool cuando el "
        "usuario pregunte 'cuánto llevamos gastando', 'cuánto te he "
        "usado hoy', 'qué uso le has dado esta semana', 'qué tools "
        "uso más', etc. Por defecto 'today'; usa '7d' / '30d' para "
        "ventanas más amplias."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "period": types.Schema(
                type=types.Type.STRING,
                description=(
                    "'today' (default), '7d' o '30d'. Si el usuario "
                    "dice 'esta semana' usa 7d; 'este mes' / 'últimos "
                    "30 días' usa 30d."
                ),
            ),
        },
    ),
    handler=_usage_stats,
)
