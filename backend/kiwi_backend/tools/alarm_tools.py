"""Tools de despertadores / alarmas."""
from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from google.genai import types

from .. import alarms
from .registry import DEFAULT_TIMEZONE, ToolResult, register


def _alarm_list_scene(items: list[alarms.Alarm]) -> dict[str, Any]:
    return {"type": "alarm_list", "items": alarms.to_wire(items)}


def _parse_when(when_iso: str) -> int:
    """Parse an ISO 8601 datetime in the user's TZ to epoch-ms.

    Accepts both naive ('2026-05-08T07:00:00') and aware
    ('2026-05-08T07:00:00+02:00') strings. Naive values are
    interpreted as DEFAULT_TIMEZONE (Madrid) — the common case for
    Gemini ("a las 7" → it omits the offset).
    """
    try:
        dt = datetime.fromisoformat(when_iso)
    except ValueError as exc:
        raise ValueError(f"when no es ISO 8601: {when_iso!r}") from exc
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=ZoneInfo(DEFAULT_TIMEZONE))
    return int(dt.timestamp() * 1000)


async def _alarm_set(when: str = "", label: str = "") -> ToolResult:
    """Schedule a one-shot alarm at the given ISO 8601 datetime."""
    if not when:
        return ToolResult(
            response={"error": "when requerido (ISO 8601, ej '2026-05-08T07:00:00')"},
        )
    try:
        fires_at_ms = _parse_when(when)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    try:
        alarm = await asyncio.to_thread(alarms.set_alarm, fires_at_ms, label)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={
            "scheduled": {
                "id": alarm.id,
                "fires_at_ms": alarm.fires_at_ms,
                "label": alarm.label,
            },
            "count": len(items),
        },
        scene=_alarm_list_scene(items),
    )


async def _alarm_cancel(match: str = "") -> ToolResult:
    if not match.strip():
        return ToolResult(response={"error": "match requerido (id o etiqueta)"})
    try:
        cancelled = await asyncio.to_thread(alarms.cancel, match)
    except alarms.AlarmNotFoundError:
        items = await asyncio.to_thread(alarms.list_active)
        return ToolResult(response={
            "error": (
                f"no se encontró despertador {match!r}. Activos: "
                f"{[a.label or a.id for a in items]}"
            ),
        })
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={
            "cancelled": {"id": cancelled.id, "label": cancelled.label},
            "count": len(items),
        },
        scene=_alarm_list_scene(items),
    )


async def _alarm_list() -> ToolResult:
    items = await asyncio.to_thread(alarms.list_active)
    return ToolResult(
        response={"count": len(items), "items": alarms.to_wire(items)},
        scene=_alarm_list_scene(items),
    )


register(
    name="alarm_set",
    description=(
        "Programa un despertador / alarma para una hora concreta. "
        "Llama cuando el usuario diga 'despiértame mañana a las 7', "
        "'avísame el viernes a las 8 y media', 'ponme una alarma a las "
        "21:30', etc. Convierte el momento a ISO 8601 en hora local "
        "(zona Europe/Madrid si el usuario no dice otra cosa). Acepta "
        "una etiqueta opcional para distinguirlo de otros despertadores. "
        "Pueden coexistir varios despertadores activos a la vez."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "when": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Momento en formato ISO 8601 ('2026-05-08T07:00:00' "
                    "o con offset '2026-05-08T07:00:00+02:00'). Sin "
                    "offset se asume Europe/Madrid. Debe ser futuro."
                ),
            ),
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta breve opcional ('trabajo', 'gimnasio', "
                    "'tren'). Se muestra debajo de la hora cuando suena."
                ),
            ),
        },
        required=["when"],
    ),
    handler=_alarm_set,
)

register(
    name="alarm_cancel",
    description=(
        "Cancela un despertador activo. Llama cuando el usuario diga "
        "'cancela el despertador', 'quita la alarma de las 7', "
        "'borra el de mañana', etc. Pasa en `match` un trozo del "
        "label o el id si lo conoces; el backend hace coincidencia "
        "fuzzy. Si no es única la coincidencia, devuelve la lista de "
        "activos."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description="Etiqueta (o trozo) o id del despertador a cancelar.",
            ),
        },
        required=["match"],
    ),
    handler=_alarm_cancel,
)

register(
    name="alarm_list",
    description=(
        "Lista los despertadores activos y los muestra en pantalla. "
        "Llama cuando el usuario pregunte 'qué alarmas tengo', "
        "'qué despertadores hay puestos', 'a qué hora me avisas "
        "mañana', etc."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_alarm_list,
)
