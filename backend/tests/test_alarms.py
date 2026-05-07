"""Tests for the alarms persistence module.

Stubs state_store with an in-memory dict so the suite stays hermetic.
"""
from __future__ import annotations

import time
from typing import Any

import pytest

from kiwi_backend import alarms, state_store


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


def test_list_active_empty_initially() -> None:
    assert alarms.list_active() == []


def test_set_alarm_persists() -> None:
    fires = int(time.time() * 1000) + 60_000
    a = alarms.set_alarm(fires, label="trabajo")
    assert a.label == "trabajo"
    listed = alarms.list_active()
    assert len(listed) == 1
    assert listed[0].id == a.id


def test_set_alarm_rejects_past() -> None:
    with pytest.raises(ValueError):
        alarms.set_alarm(int(time.time() * 1000) - 1000, label="x")


def test_multiple_alarms_sorted_by_fires_at() -> None:
    now = int(time.time() * 1000)
    alarms.set_alarm(now + 60_000 * 60, label="late")
    alarms.set_alarm(now + 60_000 * 30, label="mid")
    alarms.set_alarm(now + 60_000, label="soon")
    labels = [a.label for a in alarms.list_active()]
    assert labels == ["soon", "mid", "late"]


def test_cancel_by_label_fuzzy() -> None:
    now = int(time.time() * 1000)
    alarms.set_alarm(now + 60_000, label="trabajo")
    alarms.set_alarm(now + 120_000, label="gimnasio")
    cancelled = alarms.cancel("traba")
    assert cancelled.label == "trabajo"
    assert [a.label for a in alarms.list_active()] == ["gimnasio"]


def test_cancel_unknown_raises() -> None:
    with pytest.raises(alarms.AlarmNotFoundError):
        alarms.cancel("phantom")


def test_dismiss_drops_after_ring() -> None:
    now = int(time.time() * 1000)
    a = alarms.set_alarm(now + 60_000, label="x")
    dropped = alarms.dismiss(a.id)
    assert dropped is not None
    assert dropped.id == a.id
    assert alarms.list_active() == []


def test_dismiss_unknown_returns_none() -> None:
    assert alarms.dismiss("nope") is None


def test_snooze_pushes_fires_at() -> None:
    now = int(time.time() * 1000)
    a = alarms.set_alarm(now + 60_000, label="x")
    updated = alarms.snooze(a.id, minutes=10)
    expected_min = int(time.time() * 1000) + 9 * 60_000
    assert updated.fires_at_ms >= expected_min


def test_snooze_unknown_raises() -> None:
    with pytest.raises(alarms.AlarmNotFoundError):
        alarms.snooze("phantom", minutes=10)


def test_list_active_prunes_stale(_fake_state: dict[str, Any]) -> None:
    """Alarms more than 1h in the past auto-prune on read."""
    stale_ms = int(time.time() * 1000) - 2 * 60 * 60 * 1000
    fresh_ms = int(time.time() * 1000) + 60_000
    _fake_state["alarms.json"] = [
        {"id": "stale", "fires_at_ms": stale_ms, "label": "old", "created_ms": 0},
        {"id": "fresh", "fires_at_ms": fresh_ms, "label": "new", "created_ms": 0},
    ]
    listed = alarms.list_active()
    assert [a.id for a in listed] == ["fresh"]
    # Persisted: stale gone after this read.
    assert len(_fake_state["alarms.json"]) == 1


def test_to_wire_shape() -> None:
    now = int(time.time() * 1000)
    a = alarms.set_alarm(now + 60_000, label="trabajo")
    wire = alarms.to_wire([a])
    assert wire == [{
        "id": a.id,
        "fires_at_ms": a.fires_at_ms,
        "label": "trabajo",
    }]
