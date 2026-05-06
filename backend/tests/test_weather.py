"""Tests for the Open-Meteo weather wrapper.

Net calls are stubbed via monkeypatching ``requests.get`` so the suite
stays hermetic.
"""
from __future__ import annotations

from typing import Any

import pytest

from kiwi_backend import weather


class _FakeResponse:
    def __init__(self, json_data: dict[str, Any], status: int = 200) -> None:
        self._data = json_data
        self.status_code = status

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise weather.requests.HTTPError(f"status {self.status_code}")

    def json(self) -> dict[str, Any]:
        return self._data


@pytest.fixture(autouse=True)
def _reset() -> None:
    weather.reset_cache()
    yield
    weather.reset_cache()


def test_current_returns_snapshot_on_happy_path(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, Any] = {}

    def fake_get(url: str, params: dict, timeout: int):  # noqa: ARG001
        captured["params"] = params
        return _FakeResponse({
            "current": {
                "time": "2026-05-06T14:00",
                "temperature_2m": 18.6,
                "weather_code": 2,
            },
        })

    monkeypatch.setattr(weather.requests, "get", fake_get)

    snap = weather.current()
    assert snap is not None
    assert snap.temperature_c == pytest.approx(18.6)
    assert snap.weather_code == 2
    assert snap.description == "Parcialmente nublado"
    assert snap.icon == "partly_cloudy"
    # latitude/longitude come from settings (Madrid defaults).
    assert "latitude" in captured["params"]
    assert "longitude" in captured["params"]


def test_current_caches_within_ttl(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = {"n": 0}

    def fake_get(url: str, params: dict, timeout: int):  # noqa: ARG001
        calls["n"] += 1
        return _FakeResponse({
            "current": {"temperature_2m": 12.0, "weather_code": 0},
        })

    monkeypatch.setattr(weather.requests, "get", fake_get)

    weather.current()
    weather.current()
    weather.current()
    assert calls["n"] == 1


def test_current_returns_stale_cache_on_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    # First call succeeds.
    def first_get(url: str, params: dict, timeout: int):  # noqa: ARG001
        return _FakeResponse({
            "current": {"temperature_2m": 20.0, "weather_code": 0},
        })

    monkeypatch.setattr(weather.requests, "get", first_get)
    first = weather.current()
    assert first is not None

    # Force a fresh fetch and have it fail.
    weather._cached_at = 0.0  # type: ignore[attr-defined]

    def boom(url: str, params: dict, timeout: int):  # noqa: ARG001
        raise weather.requests.ConnectionError("network down")

    monkeypatch.setattr(weather.requests, "get", boom)
    second = weather.current()
    assert second is first  # falls back to the previous snapshot


def test_current_returns_none_when_first_fetch_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(url: str, params: dict, timeout: int):  # noqa: ARG001
        raise weather.requests.Timeout("slow")

    monkeypatch.setattr(weather.requests, "get", boom)
    assert weather.current() is None


@pytest.mark.parametrize(
    "code,expected_icon",
    [
        (0, "clear"),
        (1, "partly_cloudy"),
        (2, "partly_cloudy"),
        (3, "cloudy"),
        (45, "fog"),
        (61, "rain"),
        (82, "rain"),
        (75, "snow"),
        (95, "storm"),
        (12345, "cloudy"),  # unknown → safe fallback
    ],
)
def test_icon_mapping(code: int, expected_icon: str) -> None:
    assert weather._icon(code) == expected_icon


def test_to_wire_rounds_temperature() -> None:
    snap = weather.CurrentWeather(
        temperature_c=18.66666,
        weather_code=2,
        description="Parcialmente nublado",
        icon="partly_cloudy",
    )
    wire = weather.to_wire(snap)
    assert wire == {
        "temperature_c": 18.7,
        "weather_code": 2,
        "description": "Parcialmente nublado",
        "icon": "partly_cloudy",
    }
