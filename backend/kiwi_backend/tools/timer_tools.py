"""Tools de temporizador (kitchen-style countdown)."""
from __future__ import annotations

from typing import Any

from google.genai import types

from .. import timer
from .registry import ToolResult, register


def _timer_scene(active: timer.ActiveTimer) -> dict[str, Any]:
    return {"type": "timer", **timer.to_wire(active)}


def _timer_start(
    duration_seconds: int = 0,
    minutes: int = 0,
    hours: int = 0,
    label: str = "",
) -> ToolResult:
    """Start a kitchen-style timer. Replaces any active one."""
    total = int(duration_seconds) + int(minutes) * 60 + int(hours) * 3600
    if total <= 0:
        return ToolResult(
            response={
                "error": (
                    "duración inválida. Pasa duration_seconds, minutes "
                    "u hours con un valor positivo."
                ),
            },
        )
    try:
        active = timer.start(total, label=label)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    return ToolResult(
        response={
            "started": True,
            "remaining_seconds": total,
            "label": active.label,
        },
        scene=_timer_scene(active),
    )


def _timer_cancel() -> ToolResult:
    cancelled = timer.cancel()
    if cancelled is None:
        return ToolResult(response={"cancelled": False, "reason": "no active timer"})
    # Push a "no timer" scene so the tablet leaves the TimerScene.
    return ToolResult(
        response={"cancelled": True, "label": cancelled.label},
        scene={"type": "timer", "ends_at_ms": 0, "label": "", "remaining_seconds": 0},
    )


def _timer_status() -> ToolResult:
    active = timer.current()
    if active is None:
        return ToolResult(response={"active": False})
    return ToolResult(
        response={"active": True, **timer.to_wire(active)},
        scene=_timer_scene(active),
    )


register(
    name="timer_start",
    description=(
        "Inicia un temporizador en el tablet. Llama a este tool cuando "
        "el usuario diga 'ponme un temporizador de X', 'avísame en X', "
        "'cuenta atrás de X', etc. Acepta duración en segundos, minutos "
        "y/o horas (combínalos si el usuario dice '1 hora y media'). "
        "Si añade un nombre ('para la pasta'), pásalo en label. "
        "Sustituye cualquier temporizador previo (un solo timer activo "
        "a la vez). El tablet pinta la cuenta atrás y suena al acabar."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "duration_seconds": types.Schema(
                type=types.Type.INTEGER,
                description="Segundos. Combinable con minutes/hours.",
            ),
            "minutes": types.Schema(
                type=types.Type.INTEGER,
                description="Minutos. Combinable con seconds/hours.",
            ),
            "hours": types.Schema(
                type=types.Type.INTEGER,
                description="Horas. Combinable con seconds/minutes.",
            ),
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta breve, opcional ('pasta', 'horno', 'lavadora'). "
                    "Se muestra debajo de la cuenta atrás."
                ),
            ),
        },
    ),
    handler=_timer_start,
)

register(
    name="timer_cancel",
    description=(
        "Cancela el temporizador activo. Llama cuando el usuario diga "
        "'cancela el temporizador', 'quita la cuenta atrás', 'olvida el "
        "timer', etc. Si no hay ninguno activo, simplemente confírmalo."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_timer_cancel,
)

register(
    name="timer_status",
    description=(
        "Devuelve cuánto le queda al temporizador activo. Llama cuando "
        "el usuario pregunte 'cuánto queda', 'cuánto falta', 'cuánto "
        "tiempo tengo aún', etc. Si no hay temporizador, dilo en lugar "
        "de inventar."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_timer_status,
)
