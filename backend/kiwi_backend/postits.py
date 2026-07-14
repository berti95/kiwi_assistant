"""Post-its de colores para la home.

Notas rápidas — más ligeras que un TODO (no se marcan como
"hecho", no tienen ciclo de vida) y más volátiles que un evento
del calendario. Sirven para pegar recordatorios cortos que se
ven de un vistazo en el home: "no olvides las llaves de casa
del vecino", "chuleta wifi: PISO_ABAJO / 12345".

Persistencia:
    {"id": "01JXX…",
     "text": "chuleta wifi: X",
     "color": "yellow",
     "created_ms": 1730000000000}

Colores permitidos (`ALLOWED_COLORS`) — mapean 1-a-1 con el enum
que la UI reconoce; cualquier otro valor lo trata como "yellow"
para que un tool call con typo no rompa la home.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import asdict, dataclass

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)

# Máximo de post-its que se muestran en la home. Más que esto empieza
# a competir con las cards y a ser ruido visual: si el usuario los
# usa como TODOs debería pasar a la lista real.
MAX_HOME_POSTITS = 6

# Los colores viven aquí en vez de en la UI porque la voz también
# los usa ("post-it verde que diga…") — si Android quiere añadir uno
# nuevo hay que ampliar el enum en ambos lados.
ALLOWED_COLORS: frozenset[str] = frozenset({
    "yellow", "pink", "blue", "green", "orange", "purple",
})
DEFAULT_COLOR = "yellow"


@dataclass(frozen=True)
class PostIt:
    id: str
    text: str
    color: str
    created_ms: int


def _path() -> str:
    return settings.kiwi_state_postits_path


def _load() -> list[PostIt]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("postits.json is not a list (got %s); treating as empty", type(raw))
        return []
    out: list[PostIt] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            color = str(entry.get("color") or DEFAULT_COLOR)
            if color not in ALLOWED_COLORS:
                color = DEFAULT_COLOR
            out.append(PostIt(
                id=str(entry["id"]),
                text=str(entry["text"]),
                color=color,
                created_ms=int(entry["created_ms"]),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed postit entry: %r", entry)
    return out


def _save(items: list[PostIt]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def list_all() -> list[PostIt]:
    """Post-its ordenados por más reciente primero (así el usuario ve
    lo último pegado antes de escrollear)."""
    items = _load()
    items.sort(key=lambda it: it.created_ms, reverse=True)
    return items


def add(text: str, color: str = DEFAULT_COLOR) -> PostIt:
    """Añade un post-it. Color inválido cae al default en vez de tirar."""
    text = text.strip()
    if not text:
        raise ValueError("postit text is empty")
    if color not in ALLOWED_COLORS:
        color = DEFAULT_COLOR
    item = PostIt(
        id=uuid.uuid4().hex[:12],
        text=text,
        color=color,
        created_ms=int(time.time() * 1000),
    )
    items = _load()
    items.append(item)
    _save(items)
    return item


class PostItNotFoundError(LookupError):
    """No post-it matched the matcher (id exacto o fuzzy sobre texto)."""


def _find(items: list[PostIt], matcher: str) -> PostIt | None:
    matcher = matcher.strip()
    if not matcher:
        return None
    for it in items:
        if it.id == matcher:
            return it
    return state_store.fuzzy_match_one(items, matcher, key=lambda it: it.text)


def remove(matcher: str) -> PostIt:
    """Borra un post-it. Lanza PostItNotFoundError si no hay match."""
    items = _load()
    target = _find(items, matcher)
    if target is None:
        raise PostItNotFoundError(matcher)
    items.remove(target)
    _save(items)
    return target


def clear() -> int:
    """Borra todos los post-its. Devuelve cuántos había."""
    items = _load()
    _save([])
    return len(items)


def to_wire(items: list[PostIt]) -> list[dict]:
    return [
        {
            "id": it.id,
            "text": it.text,
            "color": it.color,
            "created_ms": it.created_ms,
        }
        for it in items
    ]
