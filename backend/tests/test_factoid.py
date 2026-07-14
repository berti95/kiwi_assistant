"""Tests para el módulo factoid (widget "Hoy en el mundo")."""
from __future__ import annotations

from datetime import date

import pytest
import requests

from kiwi_backend import factoid


@pytest.fixture(autouse=True)
def _clear_cache() -> None:
    factoid.reset_cache()


class _FakeResponse:
    def __init__(self, payload: dict, status: int = 200) -> None:
        self._payload = payload
        self.status_code = status

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"status {self.status_code}")


def test_returns_none_on_network_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*args: object, **kwargs: object) -> None:  # noqa: ARG001
        raise requests.ConnectionError("network down")

    monkeypatch.setattr(requests, "get", boom)
    assert factoid.for_today(date(2026, 5, 7)) is None


def test_returns_none_on_empty_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(requests, "get", lambda *a, **kw: _FakeResponse({}))  # noqa: ARG005
    assert factoid.for_today(date(2026, 5, 7)) is None


def test_picks_oldest_event(monkeypatch: pytest.MonkeyPatch) -> None:
    """El evento más antiguo (más icónico) manda — es determinista."""
    payload = {
        "events": [
            {"year": 2020, "text": "Some recent tv show"},
            {"year": 1945, "text": "End of WWII"},
            {"year": 1969, "text": "Something in 1969"},
        ],
    }
    monkeypatch.setattr(requests, "get", lambda *a, **kw: _FakeResponse(payload))  # noqa: ARG005
    got = factoid.for_today(date(2026, 5, 7))
    assert got is not None
    assert got.year == 1945
    assert "WWII" in got.text


def test_skips_entries_without_text(monkeypatch: pytest.MonkeyPatch) -> None:
    payload = {
        "events": [
            {"year": 1200, "text": "  "},  # blank, se salta
            {"year": 1500, "text": "Real event"},
        ],
    }
    monkeypatch.setattr(requests, "get", lambda *a, **kw: _FakeResponse(payload))  # noqa: ARG005
    got = factoid.for_today(date(2026, 5, 7))
    assert got is not None
    assert got.year == 1500


def test_caches_per_day_key(monkeypatch: pytest.MonkeyPatch) -> None:
    """Segunda llamada el mismo día no hace HTTP."""
    hit_count = {"n": 0}
    payload = {"events": [{"year": 1900, "text": "OK"}]}

    def counting(*args: object, **kwargs: object) -> _FakeResponse:  # noqa: ARG001
        hit_count["n"] += 1
        return _FakeResponse(payload)

    monkeypatch.setattr(requests, "get", counting)
    factoid.for_today(date(2026, 5, 7))
    factoid.for_today(date(2026, 5, 7))
    assert hit_count["n"] == 1


def test_wire_shape() -> None:
    f = factoid.Factoid(date_iso="2026-05-07", year=1945, text="test")
    wire = factoid.to_wire(f)
    assert wire == {"date": "2026-05-07", "year": 1945, "text": "test"}
