"""Core del subsistema de tools: registro + dispatch.

Esta capa NO conoce ninguna tool concreta. Sólo define:
 - ``ToolResult`` (DTO devuelto por tools que también pinta escena).
 - ``SessionEndRequested`` (excepción para "cerrar conversación").
 - ``register(...)`` (registro de una tool con su FunctionDeclaration).
 - ``gemini_tools()`` (lista completa lista para ``LiveConnectConfig``).
 - ``dispatch(...)`` (run-one-tool con inyección opcional de
   ``on_scene`` para handlers que orquestan push+espera+retry, como
   ``spotify_play`` cuando despierta Spotify en el tablet).
 - ``registered_names()`` (debug / tests).

Cada grupo de tools vive en su propio submódulo
(``tools/calendar.py``, ``tools/youtube.py``, etc.) e importa de
aquí. ``tools/__init__.py`` re-exporta esta API pública y se encarga
de cargar todos los submódulos para que registren al import.
"""
from __future__ import annotations

import inspect
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from google.genai import types

log = logging.getLogger(__name__)

# Default IANA timezone used when a tool doesn't override it. Backend
# is single-user for now and the user lives in Madrid; pull this out
# into Settings if/when we go multi-user.
DEFAULT_TIMEZONE = "Europe/Madrid"


Handler = Callable[..., Any] | Callable[..., Awaitable[Any]]
SceneSink = Callable[[dict[str, Any]], Awaitable[None]]


@dataclass(frozen=True)
class ToolResult:
    """Tool output that includes both a Gemini response and a scene push.

    Use this when a tool wants the tablet to display something
    (calendar, now-playing, video, …) while Gemini also speaks about
    it. The dispatcher hands ``scene`` to the proxy's scene-push
    callback and returns ``response`` to Gemini.
    """

    response: dict[str, Any]
    scene: dict[str, Any] | None = None


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


class SessionEndRequested(RuntimeError):  # noqa: N818  — semantic name fits better than EndError
    """Una tool ha decidido que la conversación termina.

    Lanzada por p.ej. ``end_conversation``. ``dispatch`` la re-lanza
    intencionadamente (no la convierte en ``{"error": ...}``) para que
    el caller — ``gemini.proxy`` — la capture y cierre el WS tras
    terminar este turno. El control de cierre vive en el caller, no
    en la tool, para que Gemini pueda terminar de hablar antes de que
    el proxy salga del bucle.
    """


async def dispatch(
    name: str,
    args: dict[str, Any] | None,
    on_scene: SceneSink | None = None,
) -> dict[str, Any]:
    """Run the named tool and return its JSON-serialisable response.

    If the tool returns a ``ToolResult`` with a non-None ``scene`` and
    the caller supplied an ``on_scene`` callback, the scene is pushed
    *before* the response is returned to Gemini — so the user sees
    the screen update at the same time Kiwi starts speaking about it.

    Errors are caught and returned as ``{"error": "..."}`` so Gemini
    can communicate the problem back to the user instead of the proxy
    crashing mid-turn. ``SessionEndRequested`` se re-lanza
    intencionadamente: indica un fin de sesión solicitado por la
    propia conversación (vía la tool ``end_conversation``) y el
    caller la maneja.
    """
    args = args or {}
    # Defensive: el modelo no debería incluir on_scene como argumento
    # (no está en su schema) pero filtrarlo evita que un input
    # malicioso pueda inyectarlo y suplantar nuestro sink.
    args.pop("on_scene", None)
    tool = _REGISTRY.get(name)
    if tool is None:
        log.warning("unknown tool requested: %r", name)
        return {"error": f"unknown tool: {name}"}
    try:
        # Si el handler declara ``on_scene`` (sólo unas pocas tools
        # lo necesitan para orquestar push+espera+retry dentro de
        # una misma llamada — p.ej. spotify_play despierta Spotify
        # en el tablet antes de reintentar), se lo inyectamos aquí.
        # El resto de handlers no lo declaran y por tanto no lo
        # reciben — siguen siendo funciones puras de sus args.
        call_args = dict(args)
        sig = inspect.signature(tool.handler)
        if on_scene is not None and "on_scene" in sig.parameters:
            call_args["on_scene"] = on_scene
        result = tool.handler(**call_args)
        if inspect.isawaitable(result):
            result = await result
    except SessionEndRequested:
        raise
    except Exception as exc:  # noqa: BLE001  — we surface anything to the LLM
        log.exception("tool %r raised", name)
        return {"error": f"{type(exc).__name__}: {exc}"}

    if isinstance(result, ToolResult):
        if result.scene is not None and on_scene is not None:
            try:
                await on_scene(result.scene)
            except Exception:
                # A failed scene push must not break the tool response —
                # Gemini still has to answer the user.
                log.exception("on_scene callback failed for tool %r", name)
        return result.response

    if not isinstance(result, dict):
        # Wrap scalars / lists so the response payload is always a
        # JSON object — Gemini is strict about that.
        result = {"result": result}
    return result


def registered_names() -> list[str]:
    """Useful for tests + debug logs."""
    return sorted(_REGISTRY)
