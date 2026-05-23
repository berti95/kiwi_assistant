"""Single-timer state for the voice-driven kitchen-style temporizador.

Backend keeps just enough state to answer "cuánto queda" by voice and
to push a fresh Scene.Timer when a tablet reconnects mid-countdown.
The countdown itself runs on the tablet (it owns the clock the user
sees and the alarm sound). We only store ``ends_at_ms`` so a query
or a re-push remains accurate without any timer thread.

Single timer at a time — overwriting on a new ``timer_start`` matches
how kitchen timers work and keeps the wire format trivial. Multi-timer
support is a future iteration when the use case shows up.

State is process-local: a Cloud Run revision rollover loses an active
timer. With ``min-instances=1`` rollovers are rare; an extra retry by
the user is cheaper than persisting state across deploys.
"""
from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass(frozen=True)
class ActiveTimer:
    ends_at_ms: int
    label: str  # may be empty when the user didn't give one


_active: ActiveTimer | None = None


def start(duration_seconds: int, label: str = "") -> ActiveTimer:
    """Start (or replace) the active timer."""
    global _active
    if duration_seconds <= 0:
        raise ValueError("duration must be positive")
    ends_at_ms = int(time.time() * 1000) + duration_seconds * 1000
    _active = ActiveTimer(ends_at_ms=ends_at_ms, label=label.strip())
    return _active


def cancel() -> ActiveTimer | None:
    """Clear the active timer; returns the cancelled snapshot or None."""
    global _active
    cancelled = _active
    _active = None
    return cancelled


def current() -> ActiveTimer | None:
    """Return the active timer, dropping it if it has already expired.

    The tablet rings the alarm when its own clock hits the deadline, so
    after that point the backend doesn't need to remember the timer.
    Auto-prune means a stale ``ActiveTimer`` doesn't linger forever.
    """
    global _active
    if _active is None:
        return None
    if _active.ends_at_ms <= int(time.time() * 1000):
        _active = None
        return None
    return _active


def reset() -> None:
    """Drop state — useful for tests."""
    global _active
    _active = None


def to_wire(t: ActiveTimer) -> dict:
    """Wire-format DTO for both Scene.Timer and the timer_status tool."""
    remaining_ms = max(0, t.ends_at_ms - int(time.time() * 1000))
    return {
        "ends_at_ms": t.ends_at_ms,
        "label": t.label,
        "remaining_seconds": remaining_ms // 1000,
    }
