"""Lista de la compra.

Entidad separada de los TODOs porque comparten muy poco semánticamente:
los TODOs son ideas / tareas a ejecutar; la compra es una lista de
items perecedera que se vacía cada vez que vas al super. Reusamos
tooling (state_store, fuzzy match) y el patrón de tap-to-toggle de
TODOs, pero el blob es propio.

Almacenamiento: un único JSON en GCS (mismo bucket que TODOs y alarms,
distinto path). El `Item.completed_ms != None` significa "ya está en
el carro / ya lo compré"; se mantiene en la lista hasta que el usuario
diga "ya he hecho la compra" (clear) o lo borre por gestos.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import asdict, dataclass

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ShoppingItem:
    id: str
    text: str
    created_ms: int
    completed_ms: int | None = None


class ShoppingItemNotFoundError(LookupError):
    """No item matched (or the match was ambiguous)."""


def _path() -> str:
    return settings.kiwi_state_shopping_path


def _load() -> list[ShoppingItem]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("shopping.json is not a list (got %s); treating empty", type(raw))
        return []
    out: list[ShoppingItem] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            out.append(ShoppingItem(
                id=str(entry["id"]),
                text=str(entry["text"]),
                created_ms=int(entry["created_ms"]),
                completed_ms=(
                    int(entry["completed_ms"])
                    if entry.get("completed_ms") is not None
                    else None
                ),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed shopping entry: %r", entry)
    return out


def _save(items: list[ShoppingItem]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def list_all() -> list[ShoppingItem]:
    return _load()


def add(text: str) -> ShoppingItem:
    text = text.strip()
    if not text:
        raise ValueError("shopping text is empty")
    item = ShoppingItem(
        id=uuid.uuid4().hex[:12],
        text=text,
        created_ms=int(time.time() * 1000),
        completed_ms=None,
    )
    items = _load()
    items.append(item)
    _save(items)
    return item


def _find(items: list[ShoppingItem], matcher: str) -> ShoppingItem | None:
    matcher = matcher.strip()
    if not matcher:
        return None
    for it in items:
        if it.id == matcher:
            return it
    return state_store.fuzzy_match_one(items, matcher, key=lambda it: it.text)


def complete(matcher: str) -> ShoppingItem:
    items = _load()
    target = _find(items, matcher)
    if target is None:
        raise ShoppingItemNotFoundError(matcher)
    if target.completed_ms is not None:
        return target
    updated = ShoppingItem(
        id=target.id,
        text=target.text,
        created_ms=target.created_ms,
        completed_ms=int(time.time() * 1000),
    )
    items[items.index(target)] = updated
    _save(items)
    return updated


def remove(matcher: str) -> ShoppingItem:
    items = _load()
    target = _find(items, matcher)
    if target is None:
        raise ShoppingItemNotFoundError(matcher)
    items.remove(target)
    _save(items)
    return target


def clear_all() -> int:
    """Vacía la lista entera ("ya he hecho la compra"). Devuelve cuántos había."""
    items = _load()
    n = len(items)
    if n:
        _save([])
    return n


def to_wire(items: list[ShoppingItem]) -> list[dict]:
    return [
        {
            "id": it.id,
            "text": it.text,
            "completed": it.completed_ms is not None,
            "created_ms": it.created_ms,
        }
        for it in items
    ]
