"""Tools 'de sistema': hora actual, fin de conversación, volumen del tablet."""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from google.genai import types

from .registry import (
    DEFAULT_TIMEZONE,
    SceneSink,
    SessionEndRequested,
    ToolResult,
    register,
)

log = logging.getLogger(__name__)


def _get_current_time(timezone: str = DEFAULT_TIMEZONE) -> dict[str, str]:
    """Return the current local time. Fallback to UTC if the tz is bogus."""
    try:
        tz = ZoneInfo(timezone)
    except ZoneInfoNotFoundError:
        tz = ZoneInfo("UTC")
        timezone = "UTC"
    now = datetime.now(tz)
    return {
        "iso": now.isoformat(timespec="seconds"),
        "timezone": timezone,
        # Spanish locale-ish short form. Datetime locale formatting on
        # Cloud Run is finicky so we hand-format.
        "human": now.strftime("%H:%M del %A %d de %B de %Y"),
    }


register(
    name="get_current_time",
    description=(
        "Devuelve la hora local actual del usuario. Útil cuando el usuario "
        "pregunta la hora, el día de la semana o la fecha. "
        "El backend resuelve la hora real — nunca asumas una respuesta sin "
        "llamar a este tool."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "timezone": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Zona horaria IANA (p.ej. 'Europe/Madrid', 'UTC'). "
                    f"Por defecto {DEFAULT_TIMEZONE!r}."
                ),
            ),
        },
    ),
    handler=_get_current_time,
)


async def _end_conversation() -> dict[str, Any]:
    """Marca la conversación para cierre tras este turno.

    No cerramos aquí mismo: lanzamos [SessionEndRequested] que
    [dispatch] re-lanza al caller (gemini.proxy). El proxy deja que
    Gemini termine de hablar y luego rompe el bucle, así Kiwi puede
    decir "hasta luego" sin que el WS se corte a media frase.
    """
    raise SessionEndRequested()


register(
    name="end_conversation",
    description=(
        "Termina la conversación con el usuario. Llama a este tool "
        "cuando entiendas que el usuario quiere acabar: 'nada más', "
        "'ya está', 'gracias', 'es todo', 'hasta luego', 'adiós', "
        "'vale ya', 'ok kiwi', o cualquier matiz contextual que "
        "indique cierre (también un agradecimiento final, una "
        "despedida implícita, etc.). Después de llamarlo, di una "
        "despedida MUY breve (1-3 palabras como 'Hasta luego' o "
        "'Vale, hasta luego') y para. El sistema cerrará la "
        "conversación tras tu respuesta sin esperar más entrada. "
        "NO llames a este tool si el usuario está en mitad de algo "
        "(añadiendo TODOs, pidiendo info, etc.) — sólo cuando el "
        "contexto deja claro que terminó."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_end_conversation,
)


async def _set_volume(
    level: int | None = None,
    delta: int | None = None,
    *,
    on_scene: SceneSink | None = None,
) -> ToolResult:
    """Ajusta el volumen multimedia del tablet (stream MUSIC)."""
    if level is None and delta is None:
        return ToolResult(
            response={"error": "pasa level (0-100) o delta (-100..+100)"},
        )
    if level is not None and not 0 <= int(level) <= 100:
        return ToolResult(
            response={"error": f"level fuera de 0-100: {level}"},
        )
    if delta is not None and not -100 <= int(delta) <= 100:
        return ToolResult(
            response={"error": f"delta fuera de -100..+100: {delta}"},
        )

    payload: dict[str, Any] = {
        "type": "device_command",
        "command": "set_volume",
    }
    if level is not None:
        payload["level"] = int(level)
    if delta is not None:
        payload["delta"] = int(delta)

    if on_scene is not None:
        try:
            await on_scene(payload)
        except Exception:  # noqa: BLE001
            log.exception("device_command set_volume push failed")
    return ToolResult(response={"ok": True, **payload})


register(
    name="set_volume",
    description=(
        "Ajusta el volumen multimedia del tablet (música, vídeo, "
        "alarmas — todo va al mismo stream). Pasa SÓLO uno de los "
        "dos parámetros:\n"
        "  - `level` (0-100): volumen absoluto. 0 = silencio, 100 = "
        "máximo. Para 'silencia' / 'mute' usa level=0; para "
        "'volumen al 50%' usa level=50.\n"
        "  - `delta` (-100..+100): subir/bajar en puntos sobre el "
        "valor actual. Para 'sube el volumen' usa delta=15; para "
        "'baja un poco' usa delta=-10; para 'mucho más alto' usa "
        "delta=30. Ajusta la magnitud al matiz del usuario."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "level": types.Schema(
                type=types.Type.INTEGER,
                description="Volumen absoluto 0-100. Combinable con delta=None.",
            ),
            "delta": types.Schema(
                type=types.Type.INTEGER,
                description=(
                    "Cambio relativo en puntos (-100..+100). Positivo "
                    "= sube, negativo = baja."
                ),
            ),
        },
    ),
    handler=_set_volume,
)
