"""TODO list persistence.

A user-facing capture-list ("Kiwi, apúntame que tengo que…") that
survives restarts. Backed by a single JSON blob in Cloud Storage —
see ``state_store`` for the why.

The data model is intentionally minimal:

    {"id": "01JXX…", "text": "comprar tomates",
     "created_ms": 1730000000000, "completed_ms": null}

Order is creation-time ascending. ``list_all()`` returns items in
that order; the UI renders the same way. Completing a TODO sets
``completed_ms`` instead of removing it so we keep a paper trail
the user can scroll through ("¿qué hice ayer?") in a future scene
— for now ``remove`` is the way to drop something completely.
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
class TodoItem:
    id: str
    text: str
    created_ms: int
    completed_ms: int | None = None


def _path() -> str:
    return settings.kiwi_state_todos_path


def _load() -> list[TodoItem]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("todos.json is not a list (got %s); treating as empty", type(raw))
        return []
    out: list[TodoItem] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            out.append(TodoItem(
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
            log.warning("dropping malformed todo entry: %r", entry)
    return out


def _save(items: list[TodoItem]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def list_all() -> list[TodoItem]:
    """Return every TODO in creation order."""
    return _load()


def add(text: str) -> TodoItem:
    """Append a new TODO. Returns the created item."""
    text = text.strip()
    if not text:
        raise ValueError("todo text is empty")
    item = TodoItem(
        id=uuid.uuid4().hex[:12],
        text=text,
        created_ms=int(time.time() * 1000),
        completed_ms=None,
    )
    items = _load()
    items.append(item)
    _save(items)
    return item


def _find(items: list[TodoItem], matcher: str) -> TodoItem | None:
    """Find a TODO by id (exact) or by text (fuzzy)."""
    matcher = matcher.strip()
    if not matcher:
        return None
    for it in items:
        if it.id == matcher:
            return it
    return state_store.fuzzy_match_one(items, matcher, key=lambda it: it.text)


class TodoNotFoundError(LookupError):
    """No TODO matched the matcher (or the match was ambiguous)."""


def complete(matcher: str) -> TodoItem:
    """Mark a TODO as completed. Raises ``TodoNotFoundError`` on no match."""
    items = _load()
    target = _find(items, matcher)
    if target is None:
        raise TodoNotFoundError(matcher)
    if target.completed_ms is not None:
        return target
    updated = TodoItem(
        id=target.id,
        text=target.text,
        created_ms=target.created_ms,
        completed_ms=int(time.time() * 1000),
    )
    items[items.index(target)] = updated
    _save(items)
    return updated


def remove(matcher: str) -> TodoItem:
    """Delete a TODO. Raises ``TodoNotFoundError`` on no match."""
    items = _load()
    target = _find(items, matcher)
    if target is None:
        raise TodoNotFoundError(matcher)
    items.remove(target)
    _save(items)
    return target


def to_wire(items: list[TodoItem]) -> list[dict]:
    """Wire-format DTO list (used by tools + /api endpoints)."""
    return [
        {
            "id": it.id,
            "text": it.text,
            "completed": it.completed_ms is not None,
            "created_ms": it.created_ms,
        }
        for it in items
    ]
