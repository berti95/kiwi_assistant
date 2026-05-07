"""Open-Meteo client for current + multi-day forecast.

Open-Meteo is free, no API key, generous rate limits. We pull a
single 7-day window in one HTTP call and slice it locally for any
"qué tiempo hace mañana / el viernes" question — much cheaper than
hitting their API once per query and equally fresh as long as the
TTL stays modest.

A short TTL cache (10 min) covers the home dashboard's 5-min refresh
plus voice spikes within the window. Returns ``None`` on any failure
so the caller can degrade gracefully (the home shows the clock and
nothing else; the tool says "weather service unavailable").
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass

import requests

from .settings import settings

log = logging.getLogger(__name__)

_CACHE_TTL_S = 10 * 60
_TIMEOUT_S = 8
_FORECAST_DAYS = 7  # today + 6 future days

_API_URL = "https://api.open-meteo.com/v1/forecast"


@dataclass(frozen=True)
class CurrentWeather:
    temperature_c: float
    weather_code: int
    description: str
    icon: str


@dataclass(frozen=True)
class HourlyEntry:
    """One hour's worth of forecast data, used inside [DayForecast]."""
    hour: str  # "HH:MM"
    temperature_c: float
    weather_code: int
    icon: str
    precipitation_probability: int


@dataclass(frozen=True)
class DayForecast:
    """Daily summary + hourly breakdown for a single date.

    The summary fields come from Open-Meteo's ``daily`` group (the
    dominant weather_code is "the most severe of the day"); the hourly
    list is the same date filtered out of the ``hourly`` group.
    """
    date: str  # YYYY-MM-DD
    temp_max_c: float
    temp_min_c: float
    weather_code: int
    description: str
    icon: str
    precipitation_probability_max: int
    precipitation_sum_mm: float
    sunrise: str  # HH:MM
    sunset: str   # HH:MM
    hourly: list[HourlyEntry]


_cached_current: CurrentWeather | None = None
_cached_forecasts: dict[str, DayForecast] = {}
_cached_at: float = 0.0


def current() -> CurrentWeather | None:
    _ensure_fresh()
    return _cached_current


def forecast(date_iso: str) -> DayForecast | None:
    """Return the forecast for the given date (YYYY-MM-DD), or None."""
    _ensure_fresh()
    return _cached_forecasts.get(date_iso)


def forecast_dates() -> list[str]:
    """List of date strings the cache currently knows about (sorted)."""
    _ensure_fresh()
    return sorted(_cached_forecasts.keys())


def reset_cache() -> None:
    """Drop the cached snapshot — useful for tests."""
    global _cached_current, _cached_forecasts, _cached_at
    _cached_current = None
    _cached_forecasts = {}
    _cached_at = 0.0


# ---- DTO helpers ----------------------------------------------------


def to_wire(snap: CurrentWeather) -> dict:
    return {
        "temperature_c": round(snap.temperature_c, 1),
        "weather_code": snap.weather_code,
        "description": snap.description,
        "icon": snap.icon,
    }


def forecast_to_wire(day: DayForecast) -> dict:
    return {
        "date": day.date,
        "temp_max_c": round(day.temp_max_c, 1),
        "temp_min_c": round(day.temp_min_c, 1),
        "weather_code": day.weather_code,
        "description": day.description,
        "icon": day.icon,
        "precipitation_probability_max": day.precipitation_probability_max,
        "precipitation_sum_mm": round(day.precipitation_sum_mm, 1),
        "sunrise": day.sunrise,
        "sunset": day.sunset,
        "hourly": [
            {
                "hour": h.hour,
                "temperature_c": round(h.temperature_c, 1),
                "weather_code": h.weather_code,
                "icon": h.icon,
                "precipitation_probability": h.precipitation_probability,
            }
            for h in day.hourly
        ],
    }


# ---- internals ------------------------------------------------------


def _ensure_fresh() -> None:
    """Refresh the in-process cache if it's stale.

    Keeps the previous cache on transient failure so a flaky network
    doesn't blank /api/home or break a voice query — stale data is
    always better than no data at this granularity.
    """
    global _cached_current, _cached_forecasts, _cached_at
    now = time.time()
    if _cached_at and (now - _cached_at) < _CACHE_TTL_S:
        return
    try:
        response = requests.get(
            _API_URL,
            params={
                "latitude": settings.kiwi_weather_lat,
                "longitude": settings.kiwi_weather_lon,
                "current": "temperature_2m,weather_code",
                "daily": ",".join([
                    "temperature_2m_max",
                    "temperature_2m_min",
                    "weather_code",
                    "precipitation_probability_max",
                    "precipitation_sum",
                    "sunrise",
                    "sunset",
                ]),
                "hourly": ",".join([
                    "temperature_2m",
                    "weather_code",
                    "precipitation_probability",
                ]),
                "forecast_days": _FORECAST_DAYS,
                "timezone": "auto",
            },
            timeout=_TIMEOUT_S,
        )
        response.raise_for_status()
        payload = response.json()
    except (requests.RequestException, ValueError) as exc:
        log.warning("weather refresh failed: %s: %s", type(exc).__name__, exc)
        return

    _cached_current = _parse_current(payload)
    _cached_forecasts = _parse_forecasts(payload)
    _cached_at = now


def _parse_current(payload: dict) -> CurrentWeather | None:
    cur = payload.get("current") or {}
    temp = cur.get("temperature_2m")
    code = cur.get("weather_code")
    if temp is None or code is None:
        log.warning("weather payload missing current temperature/code: %r", cur)
        return _cached_current  # keep prior on bad shape
    return CurrentWeather(
        temperature_c=float(temp),
        weather_code=int(code),
        description=_describe(int(code)),
        icon=_icon(int(code)),
    )


def _parse_forecasts(payload: dict) -> dict[str, DayForecast]:
    daily = payload.get("daily") or {}
    hourly = payload.get("hourly") or {}

    days: list[str] = list(daily.get("time") or [])
    if not days:
        return {}

    # Group hourly entries by date once so each day slice is O(N/days).
    hourly_by_date: dict[str, list[HourlyEntry]] = {d: [] for d in days}
    times = list(hourly.get("time") or [])
    temps = list(hourly.get("temperature_2m") or [])
    codes = list(hourly.get("weather_code") or [])
    probs = list(hourly.get("precipitation_probability") or [])
    for i, ts in enumerate(times):
        # ts shape: "2026-05-07T14:00"
        if "T" not in ts:
            continue
        date, _, hour_part = ts.partition("T")
        if date not in hourly_by_date:
            continue
        try:
            code = int(codes[i]) if i < len(codes) else 0
        except (TypeError, ValueError):
            continue
        try:
            temp = float(temps[i]) if i < len(temps) else 0.0
        except (TypeError, ValueError):
            continue
        try:
            prob = int(probs[i]) if i < len(probs) and probs[i] is not None else 0
        except (TypeError, ValueError):
            prob = 0
        hourly_by_date[date].append(HourlyEntry(
            hour=hour_part[:5],  # "14:00"
            temperature_c=temp,
            weather_code=code,
            icon=_icon(code),
            precipitation_probability=prob,
        ))

    out: dict[str, DayForecast] = {}
    for i, date in enumerate(days):
        try:
            code = int((daily.get("weather_code") or [0])[i])
        except (TypeError, ValueError):
            code = 0
        try:
            tmax = float((daily.get("temperature_2m_max") or [0])[i])
            tmin = float((daily.get("temperature_2m_min") or [0])[i])
        except (TypeError, ValueError):
            continue
        try:
            prob_max = int((daily.get("precipitation_probability_max") or [0])[i] or 0)
        except (TypeError, ValueError):
            prob_max = 0
        try:
            precip = float((daily.get("precipitation_sum") or [0])[i] or 0)
        except (TypeError, ValueError):
            precip = 0.0
        sunrise_arr = daily.get("sunrise") or []
        sunset_arr = daily.get("sunset") or []
        sunrise = _hhmm(sunrise_arr[i] if i < len(sunrise_arr) else "")
        sunset = _hhmm(sunset_arr[i] if i < len(sunset_arr) else "")
        out[date] = DayForecast(
            date=date,
            temp_max_c=tmax,
            temp_min_c=tmin,
            weather_code=code,
            description=_describe(code),
            icon=_icon(code),
            precipitation_probability_max=prob_max,
            precipitation_sum_mm=precip,
            sunrise=sunrise,
            sunset=sunset,
            hourly=hourly_by_date.get(date, []),
        )
    return out


def _hhmm(iso: str) -> str:
    """Extract 'HH:MM' from an Open-Meteo timestamp ('YYYY-MM-DDTHH:MM')."""
    if not iso or "T" not in iso:
        return ""
    return iso.partition("T")[2][:5]


# ---- WMO weather code mapping --------------------------------------
#
# Open-Meteo follows the WMO 4677 codes. We collapse the long tail
# (heavy/slight/moderate sub-buckets) into a handful of human-friendly
# Spanish phrases + a coarse icon string the Android side renders into
# an emoji or a vector drawable.

_DESCRIPTIONS_ES: dict[int, str] = {
    0: "Despejado",
    1: "Casi despejado",
    2: "Parcialmente nublado",
    3: "Nublado",
    45: "Niebla",
    48: "Niebla helada",
    51: "Llovizna ligera",
    53: "Llovizna",
    55: "Llovizna intensa",
    56: "Llovizna helada",
    57: "Llovizna helada intensa",
    61: "Lluvia ligera",
    63: "Lluvia",
    65: "Lluvia intensa",
    66: "Lluvia helada",
    67: "Lluvia helada intensa",
    71: "Nevada ligera",
    73: "Nevada",
    75: "Nevada intensa",
    77: "Nieve granular",
    80: "Chubascos ligeros",
    81: "Chubascos",
    82: "Chubascos fuertes",
    85: "Chubascos de nieve",
    86: "Chubascos de nieve intensos",
    95: "Tormenta",
    96: "Tormenta con granizo",
    99: "Tormenta fuerte con granizo",
}


def _describe(code: int) -> str:
    return _DESCRIPTIONS_ES.get(code, f"Código {code}")


def _icon(code: int) -> str:
    if code == 0:
        return "clear"
    if code in (1, 2):
        return "partly_cloudy"
    if code == 3:
        return "cloudy"
    if code in (45, 48):
        return "fog"
    if 51 <= code <= 67 or 80 <= code <= 82:
        return "rain"
    if 71 <= code <= 77 or 85 <= code <= 86:
        return "snow"
    if 95 <= code <= 99:
        return "storm"
    return "cloudy"
