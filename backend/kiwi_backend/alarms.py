"""Alarm clock persistence.

User-facing despertador: "Kiwi, despiértame mañana a las 7" — one-shot
alarms (recurrence is a future iteration). Multiple alarms can be
active at once. Stored as a single GCS JSON blob mirroring the TODO
pattern, so the backend is stateless and a Cloud Run rollover doesn't
lose what's set.

The actual ringing happens on the tablet via Android's
``AlarmManager.setAlarmClock`` — the backend only persists the list
and exposes mutation tools / REST. The tablet polls /api/home, sees
the alarms slice, and reconciles its own AlarmManager scheduling.

Past alarms (fires_at_ms more than 1 h ago) are pruned on every read
so we don't accumulate ringers that already fired.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import asdict, dataclass

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)


# Drop alarms whose firing time is more than this far in the past.
# Gives the tablet time to ring + the user time to dismiss without
# the entry vanishing mid-ring on a poll.
_STALE_HORIZON_S = 60 * 60


@dataclass(frozen=True)
class Alarm:
    id: str
    fires_at_ms: int
    label: str
    created_ms: int


class AlarmNotFoundError(LookupError):
    """No alarm matched the matcher (or the match was ambiguous)."""


def _path() -> str:
    return settings.kiwi_state_alarms_path


def _load_raw() -> list[Alarm]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("alarms.json is not a list (got %s); treating empty", type(raw))
        return []
    out: list[Alarm] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            out.append(Alarm(
                id=str(entry["id"]),
                fires_at_ms=int(entry["fires_at_ms"]),
                label=str(entry.get("label") or ""),
                created_ms=int(entry.get("created_ms") or 0),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed alarm entry: %r", entry)
    return out


def _save(alarms: list[Alarm]) -> None:
    state_store.write_json(_path(), [asdict(a) for a in alarms])


def list_active() -> list[Alarm]:
    """Return non-stale alarms sorted by fires_at_ms ascending."""
    now_ms = int(time.time() * 1000)
    horizon_ms = now_ms - _STALE_HORIZON_S * 1000
    items = _load_raw()
    fresh = [a for a in items if a.fires_at_ms > horizon_ms]
    if len(fresh) != len(items):
        # Auto-prune so the on-disk list doesn't grow unboundedly.
        _save(fresh)
    return sorted(fresh, key=lambda a: a.fires_at_ms)


def set_alarm(fires_at_ms: int, label: str = "") -> Alarm:
    """Add a new one-shot alarm. Doesn't replace existing ones."""
    if fires_at_ms <= int(time.time() * 1000):
        raise ValueError("fires_at_ms must be in the future")
    alarm = Alarm(
        id=uuid.uuid4().hex[:12],
        fires_at_ms=int(fires_at_ms),
        label=label.strip(),
        created_ms=int(time.time() * 1000),
    )
    items = _load_raw()
    items.append(alarm)
    _save(items)
    return alarm


def _find(items: list[Alarm], matcher: str) -> Alarm | None:
    """Find an alarm by id (exact) or by label (fuzzy)."""
    matcher = matcher.strip()
    if not matcher:
        return None
    for a in items:
        if a.id == matcher:
            return a
    return state_store.fuzzy_match_one(items, matcher, key=lambda a: a.label)


def cancel(matcher: str) -> Alarm:
    """Cancel an alarm by id or label. Raises ``AlarmNotFoundError``."""
    items = _load_raw()
    target = _find(items, matcher)
    if target is None:
        raise AlarmNotFoundError(matcher)
    items.remove(target)
    _save(items)
    return target


def dismiss(alarm_id: str) -> Alarm | None:
    """Drop an alarm after it rang (tablet's "Apagar" path).

    No-op + returns None if the id isn't there (e.g. the user already
    cancelled by voice while the tablet was ringing).
    """
    items = _load_raw()
    for a in items:
        if a.id == alarm_id:
            items.remove(a)
            _save(items)
            return a
    return None


def snooze(alarm_id: str, minutes: int) -> Alarm:
    """Push an alarm's fires_at_ms forward by ``minutes``."""
    if minutes <= 0:
        raise ValueError("minutes must be positive")
    items = _load_raw()
    for i, a in enumerate(items):
        if a.id == alarm_id:
            updated = Alarm(
                id=a.id,
                fires_at_ms=int(time.time() * 1000) + minutes * 60_000,
                label=a.label,
                created_ms=a.created_ms,
            )
            items[i] = updated
            _save(items)
            return updated
    raise AlarmNotFoundError(alarm_id)


def to_wire(alarms: list[Alarm]) -> list[dict]:
    """Wire-format DTO list."""
    return [
        {
            "id": a.id,
            "fires_at_ms": a.fires_at_ms,
            "label": a.label,
        }
        for a in alarms
    ]
