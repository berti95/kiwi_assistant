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


# ---- forecast cache + lookup ---------------------------------------


_FORECAST_PAYLOAD = {
    "current": {"temperature_2m": 18.0, "weather_code": 2},
    "daily": {
        "time": ["2026-05-07", "2026-05-08", "2026-05-09"],
        "temperature_2m_max": [22.5, 19.1, 25.0],
        "temperature_2m_min": [13.1, 12.0, 16.5],
        "weather_code": [61, 2, 0],
        "precipitation_probability_max": [60, 20, 0],
        "precipitation_sum": [4.5, 0.2, 0.0],
        "sunrise": [
            "2026-05-07T07:23",
            "2026-05-08T07:22",
            "2026-05-09T07:21",
        ],
        "sunset": [
            "2026-05-07T20:51",
            "2026-05-08T20:52",
            "2026-05-09T20:53",
        ],
    },
    "hourly": {
        "time": [
            "2026-05-07T08:00", "2026-05-07T09:00",
            "2026-05-08T10:00",
        ],
        "temperature_2m": [14.0, 15.5, 12.5],
        "weather_code": [61, 61, 2],
        "precipitation_probability": [80, 70, 10],
    },
}


def test_forecast_returns_day_with_hourly(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        weather.requests, "get",
        lambda *_a, **_kw: _FakeResponse(_FORECAST_PAYLOAD),
    )
    day = weather.forecast("2026-05-07")
    assert day is not None
    assert day.temp_max_c == pytest.approx(22.5)
    assert day.temp_min_c == pytest.approx(13.1)
    assert day.description == "Lluvia ligera"
    assert day.icon == "rain"
    assert day.sunrise == "07:23"
    assert day.sunset == "20:51"
    assert [h.hour for h in day.hourly] == ["08:00", "09:00"]
    assert day.hourly[0].precipitation_probability == 80


def test_forecast_unknown_date_returns_none(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        weather.requests, "get",
        lambda *_a, **_kw: _FakeResponse(_FORECAST_PAYLOAD),
    )
    assert weather.forecast("2099-01-01") is None


def test_forecast_dates_lists_window(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        weather.requests, "get",
        lambda *_a, **_kw: _FakeResponse(_FORECAST_PAYLOAD),
    )
    assert weather.forecast_dates() == [
        "2026-05-07", "2026-05-08", "2026-05-09",
    ]


def test_forecast_to_wire_shape(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        weather.requests, "get",
        lambda *_a, **_kw: _FakeResponse(_FORECAST_PAYLOAD),
    )
    day = weather.forecast("2026-05-08")
    assert day is not None
    wire = weather.forecast_to_wire(day)
    assert wire["date"] == "2026-05-08"
    assert wire["temp_max_c"] == 19.1
    assert wire["sunrise"] == "07:22"
    assert isinstance(wire["hourly"], list)
    assert len(wire["hourly"]) == 1
    assert wire["hourly"][0] == {
        "hour": "10:00",
        "temperature_c": 12.5,
        "weather_code": 2,
        "icon": "partly_cloudy",
        "precipitation_probability": 10,
    }


def test_current_and_forecast_share_one_fetch(monkeypatch: pytest.MonkeyPatch) -> None:
    """One HTTP call fills both `current()` and the forecast cache."""
    calls = {"n": 0}

    def fake_get(*_a, **_kw):
        calls["n"] += 1
        return _FakeResponse(_FORECAST_PAYLOAD)

    monkeypatch.setattr(weather.requests, "get", fake_get)
    weather.current()
    weather.forecast("2026-05-07")
    weather.forecast("2026-05-08")
    weather.forecast_dates()
    assert calls["n"] == 1
