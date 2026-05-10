"""Tests for the usage telemetry module.

Stubs state_store con un dict en memoria para que los tests no toquen
GCS. Reloj fingido vía monkeypatch de time.time donde el periodo lo
necesite.
"""
from __future__ import annotations

import time
from typing import Any

import pytest

from kiwi_backend import state_store, usage


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


def test_log_conversation_persists() -> None:
    now_ms = int(time.time() * 1000)
    entry = usage.log_conversation(
        started_at_ms=now_ms,
        ended_at_ms=now_ms + 1_000,
        turn_count=3,
        audio_in_seconds=12.0,
        audio_out_seconds=18.5,
        tool_call_names=["spotify_play", "todo_add"],
    )
    assert entry.id
    aggregate = usage.aggregate("30d")
    assert aggregate["conversation_count"] == 1
    assert aggregate["turn_count"] == 3
    assert aggregate["audio_in_seconds"] == 12.0
    assert aggregate["audio_out_seconds"] == 18.5


def test_aggregate_today_filters_to_today_only() -> None:
    now_ms = int(time.time() * 1000)
    yesterday_ms = now_ms - 36 * 60 * 60 * 1000  # 36h atrás
    usage.log_conversation(
        started_at_ms=yesterday_ms,
        ended_at_ms=yesterday_ms + 5_000,
        turn_count=1,
        audio_in_seconds=5.0,
        audio_out_seconds=10.0,
        tool_call_names=[],
    )
    usage.log_conversation(
        started_at_ms=now_ms,
        ended_at_ms=now_ms + 5_000,
        turn_count=2,
        audio_in_seconds=8.0,
        audio_out_seconds=14.0,
        tool_call_names=[],
    )
    today = usage.aggregate("today")
    assert today["conversation_count"] == 1
    assert today["audio_in_seconds"] == 8.0


def test_aggregate_7d_includes_last_week() -> None:
    now_ms = int(time.time() * 1000)
    five_days_ago = now_ms - 5 * 24 * 60 * 60 * 1000
    ten_days_ago = now_ms - 10 * 24 * 60 * 60 * 1000

    usage.log_conversation(
        started_at_ms=five_days_ago, ended_at_ms=five_days_ago + 1_000,
        turn_count=1, audio_in_seconds=5.0, audio_out_seconds=10.0,
        tool_call_names=[],
    )
    usage.log_conversation(
        started_at_ms=ten_days_ago, ended_at_ms=ten_days_ago + 1_000,
        turn_count=1, audio_in_seconds=99.0, audio_out_seconds=99.0,
        tool_call_names=[],
    )

    seven = usage.aggregate("7d")
    assert seven["conversation_count"] == 1  # solo el de hace 5 días
    thirty = usage.aggregate("30d")
    assert thirty["conversation_count"] == 2


def test_top_tools_orders_by_count() -> None:
    now_ms = int(time.time() * 1000)
    usage.log_conversation(
        started_at_ms=now_ms, ended_at_ms=now_ms + 1_000,
        turn_count=1, audio_in_seconds=1.0, audio_out_seconds=1.0,
        tool_call_names=["a", "a", "b"],
    )
    usage.log_conversation(
        started_at_ms=now_ms, ended_at_ms=now_ms + 1_000,
        turn_count=1, audio_in_seconds=1.0, audio_out_seconds=1.0,
        tool_call_names=["c", "c", "c", "c", "a"],
    )
    today = usage.aggregate("today")
    counts = {t["name"]: t["count"] for t in today["top_tools"]}
    names = [t["name"] for t in today["top_tools"]]
    # c=4 > a=3 > b=1
    assert names[0] == "c"
    assert counts == {"c": 4, "a": 3, "b": 1}


def test_estimated_cost_uses_settings_rates(monkeypatch: pytest.MonkeyPatch) -> None:
    from kiwi_backend.settings import settings

    monkeypatch.setattr(settings, "kiwi_cost_audio_in_eur_per_second", 0.001)
    monkeypatch.setattr(settings, "kiwi_cost_audio_out_eur_per_second", 0.002)

    now_ms = int(time.time() * 1000)
    usage.log_conversation(
        started_at_ms=now_ms, ended_at_ms=now_ms + 1_000,
        turn_count=1, audio_in_seconds=10.0, audio_out_seconds=20.0,
        tool_call_names=[],
    )
    today = usage.aggregate("today")
    # 10 * 0.001 + 20 * 0.002 = 0.01 + 0.04 = 0.05
    assert today["estimated_cost_eur"] == pytest.approx(0.05)


def test_log_conversation_prunes_old_entries(_fake_state: dict[str, Any]) -> None:
    very_old_ms = int(time.time() * 1000) - 60 * 24 * 60 * 60 * 1000  # 60 días
    _fake_state["usage.json"] = [{
        "id": "old", "started_at_ms": very_old_ms, "ended_at_ms": very_old_ms,
        "turn_count": 1, "audio_in_seconds": 1.0, "audio_out_seconds": 1.0,
        "tool_call_names": [],
    }]
    now_ms = int(time.time() * 1000)
    usage.log_conversation(
        started_at_ms=now_ms, ended_at_ms=now_ms,
        turn_count=1, audio_in_seconds=1.0, audio_out_seconds=1.0,
        tool_call_names=[],
    )
    saved_ids = [it["id"] for it in _fake_state["usage.json"]]
    assert "old" not in saved_ids


def test_aggregate_invalid_period_raises() -> None:
    with pytest.raises(ValueError):
        usage._period_window_ms("year")  # type: ignore[arg-type]


def test_aggregate_empty_returns_zeros() -> None:
    today = usage.aggregate("today")
    assert today == {
        "period": "today",
        "conversation_count": 0,
        "turn_count": 0,
        "audio_in_seconds": 0.0,
        "audio_out_seconds": 0.0,
        "audio_total_seconds": 0.0,
        "estimated_cost_eur": 0.0,
        "top_tools": [],
    }


def test_load_skips_malformed_entries(_fake_state: dict[str, Any]) -> None:
    _fake_state["usage.json"] = [
        {
            "id": "ok", "started_at_ms": int(time.time() * 1000),
            "ended_at_ms": 2, "turn_count": 1,
            "audio_in_seconds": 1.0, "audio_out_seconds": 1.0,
            "tool_call_names": [],
        },
        {"id": "bad"},  # missing fields
        "not a dict",
    ]
    today = usage.aggregate("today")
    assert today["conversation_count"] == 1
