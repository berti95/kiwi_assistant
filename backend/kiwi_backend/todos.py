"""TODO list persistence.

A user-facing capture-list ("Kiwi, apúntame que tengo que…") that
survives restarts. Backed by a single JSON blob in Cloud Storage —
see ``state_store`` for the why.

El modelo distingue dos clases de tareas:

* ``owner="mine"`` (default): tareas del usuario. Pueden traer
  ``due_date`` (ISO ``YYYY-MM-DD``); el frontend las pinta en rojo
  cuando están vencidas y en ámbar cuando vencen hoy.
* ``owner="kiwi"``: encargos para la IA. Sin fecha — el usuario las
  acumula y luego dice "revisa la lista" para que Kiwi las ejecute.

Order is creation-time ascending. ``list_all()`` returns items in
that order; el UI agrupa por owner y reordena por due_date dentro
de "mías". Completing a TODO sets ``completed_ms`` instead of
removing it so we keep a paper trail the user can scroll through
("¿qué hice ayer?") in a future scene — for now ``remove`` is the
way to drop something completely.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import asdict, dataclass, replace
from datetime import date

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)

# Owners aceptados. Mantenemos un set explícito para validar en add()
# y en _load() — cualquier otro valor se trata como "mine" (más una
# advertencia de log) para no perder la tarea si el JSON queda raro.
_VALID_OWNERS = frozenset({"mine", "kiwi"})
DEFAULT_OWNER = "mine"


@dataclass(frozen=True)
class TodoItem:
    id: str
    text: str
    created_ms: int
    completed_ms: int | None = None
    # "mine" = tarea del usuario; "kiwi" = encargo que la IA hará
    # cuando el usuario diga "revisa la lista".
    owner: str = DEFAULT_OWNER
    # ISO ``YYYY-MM-DD``. Solo aplica a owner="mine"; en owner="kiwi"
    # se ignora (el frontend no pinta badge de fecha en esa sección).
    due_date: str | None = None


def _path() -> str:
    return settings.kiwi_state_todos_path


def _coerce_owner(raw: object, *, item_id: str) -> str:
    """Force ``raw`` to a known owner string, with a log warning otherwise."""
    if raw is None:
        return DEFAULT_OWNER  # legacy entry, sin migrar
    if isinstance(raw, str) and raw in _VALID_OWNERS:
        return raw
    log.warning(
        "todo %s has unknown owner %r; defaulting to %s",
        item_id, raw, DEFAULT_OWNER,
    )
    return DEFAULT_OWNER


def _coerce_due_date(raw: object, *, item_id: str) -> str | None:
    """Validate ISO ``YYYY-MM-DD``. Anything else → ``None`` + warning."""
    if raw is None or raw == "":
        return None
    if not isinstance(raw, str):
        log.warning("todo %s due_date not a string (%r); dropping", item_id, raw)
        return None
    try:
        date.fromisoformat(raw)
    except ValueError:
        log.warning("todo %s due_date %r is not ISO YYYY-MM-DD; dropping", item_id, raw)
        return None
    return raw


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
            item_id = str(entry["id"])
            out.append(TodoItem(
                id=item_id,
                text=str(entry["text"]),
                created_ms=int(entry["created_ms"]),
                completed_ms=(
                    int(entry["completed_ms"])
                    if entry.get("completed_ms") is not None
                    else None
                ),
                owner=_coerce_owner(entry.get("owner"), item_id=item_id),
                due_date=_coerce_due_date(entry.get("due_date"), item_id=item_id),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed todo entry: %r", entry)
    return out


def _save(items: list[TodoItem]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def list_all() -> list[TodoItem]:
    """Return every TODO in creation order."""
    return _load()


def add(
    text: str,
    *,
    owner: str = DEFAULT_OWNER,
    due_date: str | None = None,
) -> TodoItem:
    """Append a new TODO. Returns the created item.

    ``owner`` debe ser ``"mine"`` o ``"kiwi"`` (cualquier otro valor
    rebota como ``ValueError``). ``due_date``, si viene, debe ser
    ISO ``YYYY-MM-DD``; en ``owner="kiwi"`` lo descartamos
    silenciosamente — esos encargos no tienen sentido fechados.
    """
    text = text.strip()
    if not text:
        raise ValueError("todo text is empty")
    if owner not in _VALID_OWNERS:
        raise ValueError(f"unknown owner: {owner!r}")
    if owner == "kiwi":
        due_date = None
    elif due_date is not None:
        try:
            date.fromisoformat(due_date)
        except ValueError as exc:
            raise ValueError(f"due_date must be YYYY-MM-DD, got {due_date!r}") from exc
    item = TodoItem(
        id=uuid.uuid4().hex[:12],
        text=text,
        created_ms=int(time.time() * 1000),
        completed_ms=None,
        owner=owner,
        due_date=due_date,
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
    updated = replace(target, completed_ms=int(time.time() * 1000))
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
            "owner": it.owner,
            "due_date": it.due_date,
        }
        for it in items
    ]
