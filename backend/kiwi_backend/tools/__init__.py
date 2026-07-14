"""Function-calling tools exposed to Gemini Live.

Este paquete reemplaza al ``tools.py`` monolítico original. Cada grupo
de tools vive en su propio submódulo:

  - ``registry``        — core (``ToolResult``, ``register``,
                          ``dispatch``, ``gemini_tools``,
                          ``registered_names``, ``SessionEndRequested``,
                          ``DEFAULT_TIMEZONE``).
  - ``system``          — get_current_time, end_conversation, set_volume.
  - ``calendar``        — agenda del usuario.
  - ``youtube``         — búsqueda + reproducción + listas.
  - ``spotify``         — estado, control y transferencia entre devices.
  - ``todo_tools``      — TODOs persistentes.
  - ``shopping_tools``  — lista de la compra.
  - ``alarm_tools``     — despertadores.
  - ``timer_tools``     — temporizador.
  - ``weather_tools``   — tiempo actual + previsión.
  - ``usage_tools``     — estadísticas / coste.

Este ``__init__.py``:
  1. Re-exporta la API pública (``register``, ``dispatch``, ...) y
     algunas piezas internas que callers existentes (``main.py``,
     tests) accedían como ``tools.X`` antes del split.
  2. Importa cada submódulo para que su ``register(...)`` se ejecute
     como side-effect del primer import del paquete.

Si añades un nuevo grupo, créalo como submódulo y añádelo a la lista
de side-effect imports al final del archivo.

Las agrupaciones de imports con comentarios explicativos son
intencionadas — ``ruff: noqa: I001`` para que no se reordenen.
"""
# ruff: noqa: I001
from __future__ import annotations

# Stdlib re-export — tests existentes hacen
# ``monkeypatch.setattr(tools.asyncio, "sleep", ...)`` para evitar
# esperas reales. Modules are singletons en CPython, así que parchear
# aquí parchea también la referencia en ``spotify.py`` (y resto).
import asyncio  # noqa: F401

# Re-export de módulos hermanos para que los tests sigan haciendo
# ``monkeypatch.setattr(tools.<mod>, ...)``. Mismo singleton argument.
from .. import alarms, google_auth  # noqa: F401

# Public API surface (mismos nombres que exportaba el módulo monolítico).
from .registry import (  # noqa: F401
    DEFAULT_TIMEZONE,
    SessionEndRequested,
    ToolResult,
    _REGISTRY,
    dispatch,
    gemini_tools,
    register,
    registered_names,
)

# Helpers internos que main.py / tests usan directamente.
from .calendar import (  # noqa: F401
    _calendar_event_dto,
    _calendar_window,
    _list_events_blocking,
)
from .spotify import (  # noqa: F401
    _spotify_currently_playing_blocking,
    _spotify_recently_played_blocking,
)
from .youtube import (  # noqa: F401
    _YT_MAX_RESULTS_HARD_CAP,
    _parse_iso8601_duration,
    _resolve_playlist_id,
    _youtube_thumbnail,
)

# Side-effect imports: cada submódulo llama a ``register(...)`` al
# importarse. El orden no importa funcionalmente; lo mantenemos
# alfabético para que sea fácil ver de un vistazo qué grupos hay.
from . import (  # noqa: F401
    alarm_tools,
    postit_tools,
    shopping_tools,
    system,
    timer_tools,
    todo_tools,
    usage_tools,
    weather_tools,
)

__all__ = [
    "DEFAULT_TIMEZONE",
    "SessionEndRequested",
    "ToolResult",
    "dispatch",
    "gemini_tools",
    "register",
    "registered_names",
]
