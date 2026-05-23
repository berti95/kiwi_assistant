"""Tests for the in-process timer state."""
from __future__ import annotations

import time

import pytest

from kiwi_backend import timer


@pytest.fixture(autouse=True)
def _reset() -> None:
    timer.reset()
    yield
    timer.reset()


def test_no_timer_initially() -> None:
    assert timer.current() is None


def test_start_persists_until_expiry() -> None:
    active = timer.start(60, label="pasta")
    assert active.label == "pasta"
    assert active.ends_at_ms > int(time.time() * 1000)
    assert timer.current() is not None


def test_start_rejects_zero_or_negative() -> None:
    with pytest.raises(ValueError):
        timer.start(0)
    with pytest.raises(ValueError):
        timer.start(-5)


def test_start_overwrites_previous() -> None:
    first = timer.start(60, label="uno")
    second = timer.start(120, label="dos")
    assert second.label == "dos"
    assert timer.current() == second
    assert timer.current() != first


def test_cancel_returns_snapshot_and_clears() -> None:
    timer.start(60, label="x")
    cancelled = timer.cancel()
    assert cancelled is not None
    assert cancelled.label == "x"
    assert timer.current() is None


def test_cancel_when_none_returns_none() -> None:
    assert timer.cancel() is None


def test_current_drops_expired_timer(monkeypatch: pytest.MonkeyPatch) -> None:
    timer.start(60)
    # Pretend a minute and a half passed.
    real_now = time.time()
    monkeypatch.setattr(time, "time", lambda: real_now + 90)
    assert timer.current() is None


def test_to_wire_contains_remaining_seconds() -> None:
    active = timer.start(120, label="horno")
    wire = timer.to_wire(active)
    assert wire["label"] == "horno"
    assert wire["ends_at_ms"] == active.ends_at_ms
    # Remaining_seconds is bounded above by 120 and won't be negative.
    assert 0 <= wire["remaining_seconds"] <= 120
