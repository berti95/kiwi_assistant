"""Function-calling tools exposed to Gemini Live.

Tools are how the LLM does anything beyond talking — get the time,
play a song, list calendar events, control the lights one day.
Each tool is:

    1. Declared via a ``FunctionDeclaration`` so Gemini knows it
       exists, what it does, and what arguments it takes.
    2. Backed by a Python ``handler`` callable that runs when Gemini
       decides to call it.

The dispatch runs on the same asyncio loop as the proxy. Handlers
can be async — anything that hits the network (Spotify, Google
Calendar, …) should be — so we don't block the audio receive loop
while a tool is executing.

Adding a new tool is a single ``register`` call. Keep them small and
focused; Gemini composes them itself when a request needs more than
one. Document parameters carefully — the model uses the descriptions
to decide when and how to call.
"""
from __future__ import annotations

import inspect
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from google.genai import types

log = logging.getLogger(__name__)

# Default IANA timezone used when a tool doesn't override it. Backend
# is single-user for now and the user lives in Madrid; pull this out
# into Settings if/when we go multi-user.
DEFAULT_TIMEZONE = "Europe/Madrid"


Handler = Callable[..., Any] | Callable[..., Awaitable[Any]]


@dataclass(frozen=True)
class _Tool:
    declaration: types.FunctionDeclaration
    handler: Handler


_REGISTRY: dict[str, _Tool] = {}


def register(
    name: str,
    description: str,
    parameters: types.Schema | None,
    handler: Handler,
) -> None:
    """Register a tool. Idempotent per ``name`` (last wins)."""
    if name in _REGISTRY:
        log.info("re-registering tool %r (overwriting previous)", name)
    _REGISTRY[name] = _Tool(
        declaration=types.FunctionDeclaration(
            name=name,
            description=description,
            parameters=parameters,
        ),
        handler=handler,
    )


def gemini_tools() -> list[types.Tool]:
    """Build the ``tools`` list to pass to ``LiveConnectConfig``.

    Gemini wants all function declarations bundled inside a single
    ``Tool`` rather than one ``Tool`` per declaration — this matches
    what their own samples do.
    """
    if not _REGISTRY:
        return []
    return [
        types.Tool(
            function_declarations=[t.declaration for t in _REGISTRY.values()],
        ),
    ]


async def dispatch(name: str, args: dict[str, Any] | None) -> dict[str, Any]:
    """Run the named tool and return a JSON-serialisable result.

    Errors are caught and returned as ``{"error": "..."}`` so Gemini
    can communicate the problem back to the user instead of the
    proxy crashing mid-turn.
    """
    args = args or {}
    tool = _REGISTRY.get(name)
    if tool is None:
        log.warning("unknown tool requested: %r", name)
        return {"error": f"unknown tool: {name}"}
    try:
        result = tool.handler(**args)
        if inspect.isawaitable(result):
            result = await result
    except Exception as exc:  # noqa: BLE001  — we surface anything to the LLM
        log.exception("tool %r raised", name)
        return {"error": f"{type(exc).__name__}: {exc}"}
    if not isinstance(result, dict):
        # Wrap scalars / lists so the response payload is always a
        # JSON object — Gemini is strict about that.
        result = {"result": result}
    return result


# ---- built-in tools -------------------------------------------------


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


def registered_names() -> list[str]:
    """Useful for tests + debug logs."""
    return sorted(_REGISTRY)


# Make it explicit that this module's import side-effect is "register
# all built-in tools". The session module imports it lazily so the
# registration happens only once per process.
__all__ = [
    "dispatch",
    "gemini_tools",
    "register",
    "registered_names",
]
