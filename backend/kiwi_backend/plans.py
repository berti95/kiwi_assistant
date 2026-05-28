"""Lista de planes / viajes / eventos especiales del usuario.

Pensada para cosas que NO viven en Google Calendar pero al usuario le
hace ilusión ver venir: el viaje a Cantabria, un concierto, una boda.
La home del tablet enseña un chip de "cuenta atrás" cuando faltan
días-milestone concretos (1, 2, 3, 4, 5, 10, 20, 30 — definidos en
``main.get_home``), así aparece de vez en cuando como una alegría sin
saturar.

Misma forma que ``todos`` / ``shopping``: blob JSON único en GCS, lista
en orden cronológico ascendente (siguiente primero).

Datos:
    {"id": "ab12…", "label": "Viaje a Cantabria",
     "date": "2026-06-05", "created_ms": 1730000000000}

Las entradas con ``date`` más de 30 días en el pasado se podan al
guardar para no acumular historia indefinidamente; el grace post-fecha
hace que la del propio día siga apareciendo aunque ese día se cierre la
sesión más tarde.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import date, datetime
from zoneinfo import ZoneInfo

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)

# Cuántos días se mantiene un plan en la lista tras su fecha antes de
# podarse. Da margen para que el día del evento siga apareciendo en el
# chip aunque el usuario ya no lo necesite, y permite borrarlo a mano
# con tranquilidad después.
_GRACE_DAYS_AFTER = 30


@dataclass(frozen=True)
class Plan:
    id: str
    label: str
    date: str  # ISO YYYY-MM-DD
    created_ms: int


def _path() -> str:
    return settings.kiwi_state_plans_path


def _parse_date(raw: str) -> date:
    """Strict ISO date; raises ValueError on bad input."""
    return date.fromisoformat(raw.strip())


def _load() -> list[Plan]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("plans.json is not a list (got %s); treating as empty", type(raw))
        return []
    out: list[Plan] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            plan_date = _parse_date(str(entry["date"]))
            out.append(Plan(
                id=str(entry["id"]),
                label=str(entry["label"]),
                date=plan_date.isoformat(),
                created_ms=int(entry["created_ms"]),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed plan entry: %r", entry)
    return out


def _save(items: list[Plan]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def _prune_old(items: list[Plan], today: date) -> list[Plan]:
    horizon = today.toordinal() - _GRACE_DAYS_AFTER
    return [p for p in items if _parse_date(p.date).toordinal() >= horizon]


def _today_local(tz_name: str) -> date:
    """Day according to the user's timezone (the trip-date sense of 'hoy')."""
    return datetime.now(ZoneInfo(tz_name)).date()


def list_all(tz_name: str = "Europe/Madrid") -> list[Plan]:
    """All plans in chronological order (earliest first), past auto-pruned."""
    items = _load()
    items = _prune_old(items, _today_local(tz_name))
    items.sort(key=lambda p: p.date)
    return items


def add(label: str, date_str: str, tz_name: str = "Europe/Madrid") -> Plan:
    """Add a plan. ``date_str`` must be ISO YYYY-MM-DD (today or future).

    The model must resolve relative expressions like "el viernes que
    viene" to an ISO date BEFORE calling this — same convention as the
    rest of the calendar-aware tools, which keeps this layer dumb.
    """
    label = label.strip()
    if not label:
        raise ValueError("plan label is empty")
    try:
        parsed = _parse_date(date_str)
    except ValueError as exc:
        raise ValueError(f"bad date {date_str!r}: {exc}") from exc
    today = _today_local(tz_name)
    if parsed < today:
        raise ValueError(f"plan date {parsed} is in the past (today is {today})")
    item = Plan(
        id=uuid.uuid4().hex[:12],
        label=label,
        date=parsed.isoformat(),
        created_ms=int(time.time() * 1000),
    )
    items = _prune_old(_load(), today)
    items.append(item)
    _save(items)
    return item


class PlanNotFoundError(LookupError):
    """No plan matched the matcher (or the match was ambiguous)."""


def _find(items: list[Plan], matcher: str) -> Plan | None:
    matcher = matcher.strip()
    if not matcher:
        return None
    for it in items:
        if it.id == matcher:
            return it
    return state_store.fuzzy_match_one(items, matcher, key=lambda it: it.label)


def remove(matcher: str, tz_name: str = "Europe/Madrid") -> Plan:
    """Delete a plan by id or fuzzy label."""
    items = _prune_old(_load(), _today_local(tz_name))
    target = _find(items, matcher)
    if target is None:
        raise PlanNotFoundError(matcher)
    items.remove(target)
    _save(items)
    return target


def days_until(plan: Plan, tz_name: str = "Europe/Madrid") -> int:
    """Días naturales desde 'hoy local' hasta la fecha del plan."""
    return (_parse_date(plan.date) - _today_local(tz_name)).days


def to_wire(items: list[Plan], tz_name: str = "Europe/Madrid") -> list[dict]:
    """Wire-format DTO used by tools + /api endpoints."""
    return [
        {
            "id": it.id,
            "label": it.label,
            "date": it.date,
            "days_until": days_until(it, tz_name),
        }
        for it in items
    ]
