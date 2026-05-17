"""Tools del tiempo: actual + previsión por día."""
from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any

from google.genai import types

from .. import weather
from .registry import register


async def _get_weather() -> dict[str, Any]:
    """Return current weather for the configured location."""
    snap = await asyncio.to_thread(weather.current)
    if snap is None:
        return {"error": "weather service unavailable"}
    return weather.to_wire(snap)


async def _get_weather_forecast(date: str = "") -> dict[str, Any]:
    """Return per-day forecast for an ISO date (YYYY-MM-DD)."""
    if not date:
        return {"error": "date required (formato YYYY-MM-DD)"}
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        return {"error": f"date debe ser YYYY-MM-DD; recibido {date!r}"}
    snap = await asyncio.to_thread(weather.forecast, date)
    if snap is None:
        available = await asyncio.to_thread(weather.forecast_dates)
        return {
            "error": (
                f"sin previsión para {date}. Fechas disponibles: {available}"
            ),
        }
    return weather.forecast_to_wire(snap)


register(
    name="get_weather",
    description=(
        "Devuelve el tiempo ACTUAL (temperatura y descripción breve) en "
        "la ubicación del usuario. Llama a este tool cuando pregunte "
        "'qué tiempo hace ahora', 'hace frío', 'está lloviendo'. Para "
        "preguntas sobre cómo va a estar el día, mañana o un día "
        "concreto usa get_weather_forecast en su lugar."
    ),
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
    handler=_get_weather,
)


register(
    name="get_weather_forecast",
    description=(
        "Devuelve la previsión del tiempo para una fecha: máxima, "
        "mínima, condición dominante, probabilidad de lluvia, salida "
        "y puesta del sol, y un desglose hora a hora. Llama a este "
        "tool cuando el usuario pregunte 'qué tiempo va a hacer hoy', "
        "'cómo va a estar mañana', 'va a llover esta tarde', 'qué "
        "tiempo hará el viernes', '¿me llevo abrigo?', etc. Si "
        "pregunta por una franja del día (mañana / tarde / noche / a "
        "las X) usa el array `hourly` para responder con precisión; "
        "si pregunta por el día en general usa el resumen diario "
        "(max, min, descripción)."
    ),
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "date": types.Schema(
                type=types.Type.STRING,
                description=(
                    "Fecha en formato ISO YYYY-MM-DD. Convierte la "
                    "petición ('hoy', 'mañana', 'el viernes', 'el día "
                    "12') a fecha concreta. Cubre hoy + 6 días."
                ),
            ),
        },
        required=["date"],
    ),
    handler=_get_weather_forecast,
)
