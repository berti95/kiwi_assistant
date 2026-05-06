"""Open-Meteo client for current-weather lookup.

Open-Meteo (open-meteo.com) is free, no API key, generous rate limits.
A short TTL cache (10 min) covers the home dashboard's 5-min refresh
plus any voice "qué tiempo hace" calls inside that window — we hit
their servers at most once every 10 min while idle.

Only "current" is exposed for now; daily/hourly forecasts can layer on
top when we want a "qué tiempo hace mañana" tool. Returns `None` on
any failure so the caller can degrade gracefully (the home shows the
clock and nothing else).
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass

import requests

from .settings import settings

log = logging.getLogger(__name__)

_CACHE_TTL_S = 10 * 60  # 10 min — covers /api/home (5 min) + voice spikes.
_TIMEOUT_S = 8

_API_URL = "https://api.open-meteo.com/v1/forecast"


@dataclass(frozen=True)
class CurrentWeather:
    """Snapshot of "right now" weather at the configured location."""
    temperature_c: float
    weather_code: int
    description: str  # Spanish-localised summary, e.g. "Parcialmente nublado"
    icon: str  # one of: clear, partly_cloudy, cloudy, fog, rain, snow, storm


_cached: CurrentWeather | None = None
_cached_at: float = 0.0


def current() -> CurrentWeather | None:
    """Return the latest cached current-weather snapshot, or fetch fresh."""
    global _cached, _cached_at
    now = time.time()
    if _cached is not None and (now - _cached_at) < _CACHE_TTL_S:
        return _cached
    try:
        response = requests.get(
            _API_URL,
            params={
                "latitude": settings.kiwi_weather_lat,
                "longitude": settings.kiwi_weather_lon,
                "current": "temperature_2m,weather_code",
                "timezone": "auto",
            },
            timeout=_TIMEOUT_S,
        )
        response.raise_for_status()
        payload = response.json()
    except (requests.RequestException, ValueError) as exc:
        log.warning("weather fetch failed: %s: %s", type(exc).__name__, exc)
        return _cached  # stale-cache on transient error; None if never fetched

    cur = payload.get("current") or {}
    temp = cur.get("temperature_2m")
    code = cur.get("weather_code")
    if temp is None or code is None:
        log.warning("weather payload missing temperature/code: %r", cur)
        return _cached

    snap = CurrentWeather(
        temperature_c=float(temp),
        weather_code=int(code),
        description=_describe(int(code)),
        icon=_icon(int(code)),
    )
    _cached = snap
    _cached_at = now
    return snap


def reset_cache() -> None:
    """Drop the cached snapshot — useful for tests."""
    global _cached, _cached_at
    _cached = None
    _cached_at = 0.0


def to_wire(snap: CurrentWeather) -> dict:
    """Wire-format DTO used by /api/home and the get_weather tool."""
    return {
        "temperature_c": round(snap.temperature_c, 1),
        "weather_code": snap.weather_code,
        "description": snap.description,
        "icon": snap.icon,
    }


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
