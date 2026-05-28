"""Tools de planes / viajes (lista aparte del calendario).

El usuario apunta cosas con las que ilusiona ("el viaje a Cantabria el
viernes que viene"); el tablet enseña un chip de cuenta atrás en la home
en días-milestone (ver ``main.get_home``) y la escena ``plans_list`` con
todo cuando lo pide explícitamente.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from google.genai import types

from .. import plans
from .registry import DEFAULT_TIMEZONE, ToolResult, register

log = logging.getLogger(__name__)


def _plans_list_scene(items: list[plans.Plan]) -> dict[str, Any]:
    return {"type": "plans_list", "items": plans.to_wire(items, DEFAULT_TIMEZONE)}


async def _plan_add(label: str = "", date: str = "") -> ToolResult:
    """Append a plan and push the updated list to the tablet."""
    if not label or not label.strip():
        return ToolResult(response={"error": "missing label"})
    if not date or not date.strip():
        return ToolResult(response={"error": "missing date"})
    try:
        item = await asyncio.to_thread(plans.add, label, date, DEFAULT_TIMEZONE)
        items = await asyncio.to_thread(plans.list_all, DEFAULT_TIMEZONE)
    except ValueError as exc:
        return ToolResult(response={"error": str(exc)})
    except Exception as exc:  # noqa: BLE001
        log.exception("plan_add failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "added": {
                "id": item.id, "label": item.label, "date": item.date,
                "days_until": plans.days_until(item, DEFAULT_TIMEZONE),
            },
            "count": len(items),
        },
        scene=_plans_list_scene(items),
    )


async def _plan_list() -> ToolResult:
    """Read the user's plans and push the list to the tablet."""
    try:
        items = await asyncio.to_thread(plans.list_all, DEFAULT_TIMEZONE)
    except Exception as exc:  # noqa: BLE001
        log.exception("plan_list failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "count": len(items),
            "items": plans.to_wire(items, DEFAULT_TIMEZONE),
        },
        scene=_plans_list_scene(items),
    )


async def _plan_remove(match: str = "") -> ToolResult:
    """Delete a plan by id or fuzzy label."""
    if not match or not match.strip():
        return ToolResult(response={"error": "missing match"})
    try:
        removed = await asyncio.to_thread(plans.remove, match, DEFAULT_TIMEZONE)
        items = await asyncio.to_thread(plans.list_all, DEFAULT_TIMEZONE)
    except plans.PlanNotFoundError:
        try:
            items = await asyncio.to_thread(plans.list_all, DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001
            items = []
        return ToolResult(
            response={
                "error": (
                    f"no plan matched {match!r}. existing: "
                    f"{[(p.label, p.date) for p in items]}"
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("plan_remove failed")
        return ToolResult(
            response={"error": f"{type(exc).__name__}: {exc}"},
        )
    return ToolResult(
        response={
            "removed": {"id": removed.id, "label": removed.label, "date": removed.date},
            "count": len(items),
        },
        scene=_plans_list_scene(items),
    )


register(
    name="plan_add",
    description=(
        "Apunta un plan, viaje o evento especial que al usuario le hace "
        "ilusión y que NO vive en su Google Calendar (un viaje, una "
        "boda, un concierto, una visita). Llama a este tool cuando el "
        "usuario diga 'apunta mi viaje a X', 'añade un plan', 'tengo "
        "viaje el viernes', 'me hace ilusión X el día Y' o similar. NO "
        "uses esto para tareas (eso es todo_add) ni para citas que "
        "sincronice con Google Calendar. La home del tablet enseñará "
        "una cuenta atrás del plan cuando falten pocos días o en "
        "milestones (1 semana, 2 semanas, 1 mes…)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "label": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Etiqueta corta del plan, en lenguaje natural y en "
                    "ESPAÑOL ('Viaje a Cantabria', 'Boda de Marta', "
                    "'Concierto de Vetusta Morla'). Sin signos de "
                    "puntuación finales. 2-6 palabras idealmente."
                ),
            ),
            "date": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Fecha del plan en ISO YYYY-MM-DD (p.ej. "
                    "'2026-06-05'). Si el usuario da una fecha relativa "
                    "('el viernes que viene', 'dentro de dos semanas'), "
                    "resuélvela tú a fecha absoluta usando "
                    "``get_current_time`` antes de invocar. La fecha "
                    "debe ser hoy o posterior — el backend rechaza "
                    "fechas pasadas."
                ),
            ),
        },
        required=["label", "date"],
    ),
    handler=_plan_add,
)

register(
    name="plan_list",
    description=(
        "Devuelve y muestra todos los planes / viajes / eventos "
        "especiales del usuario en la pantalla. Llama cuando pregunte "
        "'qué planes tengo', 'qué viajes hay', 'enséñame los planes', "
        "'qué me espera', etc. La pantalla muestra cada plan con su "
        "cuenta atrás; tu voz puede resumir cuántos hay y mencionar el "
        "más próximo."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_plan_list,
)

register(
    name="plan_remove",
    description=(
        "Borra un plan de la lista. Llama cuando el usuario diga "
        "'borra el viaje a X', 'quita el plan de Y', 'cancela mi "
        "viaje a Z' o similar. Pasa en 'match' un trozo de la "
        "etiqueta (o el id si lo conoces de un tool anterior); el "
        "backend hace coincidencia parcial. Si no hay match único "
        "devuelve un error con la lista actual para que pidas que "
        "concrete."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "match": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Trozo de la etiqueta del plan o su id. P.ej. "
                    "'Cantabria' para 'Viaje a Cantabria'."
                ),
            ),
        },
        required=["match"],
    ),
    handler=_plan_remove,
)
