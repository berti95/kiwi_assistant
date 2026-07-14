"""Tests para usage.daily_costs (sparkline del home)."""
from __future__ import annotations

from typing import Any

import pytest

from kiwi_backend import state_store, usage
from kiwi_backend.settings import settings


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    blobs: dict[str, Any] = {}
    monkeypatch.setattr(
        state_store, "read_json",
        lambda path, default: blobs.get(path, default),
    )
    monkeypatch.setattr(
        state_store, "write_json",
        lambda path, payload: blobs.__setitem__(path, payload),
    )
    return blobs


@pytest.fixture(autouse=True)
def _fixed_rates(monkeypatch: pytest.MonkeyPatch) -> None:
    """Tarifas redondas para poder comparar coste con exactitud."""
    monkeypatch.setattr(
        settings, "kiwi_cost_audio_in_eur_per_second", 0.001,
    )
    monkeypatch.setattr(
        settings, "kiwi_cost_audio_out_eur_per_second", 0.002,
    )


def _now_ms() -> int:
    """Anclamos a "ahora mismo" del sistema — el 30-day retention del
    módulo mira reloj real, así que un timestamp lejano se prunearía."""
    import time as _time
    return int(_time.time() * 1000)


def test_daily_costs_length_matches_days_requested() -> None:
    assert len(usage.daily_costs(days=7, now_ms=_now_ms())) == 7
    assert len(usage.daily_costs(days=3, now_ms=_now_ms())) == 3


def test_daily_costs_clamps_days_range() -> None:
    """Rango [1, 30]. Valores fuera se saturan sin explotar."""
    assert len(usage.daily_costs(days=0, now_ms=_now_ms())) == 1
    assert len(usage.daily_costs(days=100, now_ms=_now_ms())) == 30


def test_daily_costs_returns_zero_when_no_history() -> None:
    series = usage.daily_costs(days=7, now_ms=_now_ms())
    for entry in series:
        assert entry["cost_eur"] == 0.0
        assert entry["conversation_count"] == 0


def test_daily_costs_ordered_ascending_by_date() -> None:
    series = usage.daily_costs(days=5, now_ms=_now_ms())
    dates = [e["date"] for e in series]
    assert dates == sorted(dates)


def test_daily_costs_bucketizes_by_utc_day() -> None:
    """Un log de hoy y otro de hace 2 días caen en buckets distintos."""
    now = _now_ms()
    day_ms = 24 * 60 * 60 * 1000
    usage.log_conversation(
        started_at_ms=now - 2 * day_ms,
        ended_at_ms=now - 2 * day_ms + 1000,
        turn_count=1, audio_in_seconds=10.0, audio_out_seconds=5.0,
        tool_call_names=[],
    )
    usage.log_conversation(
        started_at_ms=now,
        ended_at_ms=now + 1000,
        turn_count=1, audio_in_seconds=20.0, audio_out_seconds=10.0,
        tool_call_names=[],
    )
    series = usage.daily_costs(days=7, now_ms=now)
    # 10*0.001 + 5*0.002 = 0.02 y 20*0.001 + 10*0.002 = 0.04
    non_zero = [(e["date"], e["cost_eur"]) for e in series if e["cost_eur"] > 0]
    assert len(non_zero) == 2
    assert non_zero[0][1] == pytest.approx(0.02)
    assert non_zero[1][1] == pytest.approx(0.04)
