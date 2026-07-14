"""Un dato/titular del día para el widget "Hoy en el mundo" del home.

Consulta la Wikipedia REST API ("on this day" — efemérides) filtrada
para español. Es gratis, sin auth, y devuelve una lista de eventos
históricos ocurridos en un DD/MM concreto a lo largo de la historia.
Elegimos uno "estable" (siempre el mismo por día) para que el widget
no cambie cada vez que se refresque la home.

Fallback silencioso: cualquier error de red / parseo devuelve None
y el home simplemente no pinta la card — mejor eso que un placeholder
"⚠️ no disponible" gritando.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date

import requests

log = logging.getLogger(__name__)

# TTL efectivo: entrada por día del año. La respuesta cambia sólo al
# cruzar la medianoche local; cacheamos por clave DD/MM que también
# se estabiliza automáticamente en tests deterministas.
_cache: dict[str, Factoid | None] = {}
_TIMEOUT_S = 6
_API_URL = "https://es.wikipedia.org/api/rest_v1/feed/onthisday/events/{month}/{day}"


@dataclass(frozen=True)
class Factoid:
    """Un dato histórico "hoy en el mundo" para el widget del home."""
    date_iso: str        # YYYY-MM-DD (calculada por el caller)
    year: int
    text: str            # Texto del evento (una línea)


def for_today(today: date | None = None) -> Factoid | None:
    """Devuelve la efeméride "sticky" del día. None si falla la API."""
    today = today or date.today()
    key = f"{today.month:02d}-{today.day:02d}"
    if key in _cache:
        return _cache[key]

    factoid = _fetch(today)
    _cache[key] = factoid
    return factoid


def reset_cache() -> None:
    """Vacía el cache — sólo para tests."""
    _cache.clear()


def to_wire(f: Factoid) -> dict:
    return {
        "date": f.date_iso,
        "year": f.year,
        "text": f.text,
    }


# ---- internals ------------------------------------------------------


def _fetch(today: date) -> Factoid | None:
    url = _API_URL.format(month=f"{today.month:02d}", day=f"{today.day:02d}")
    try:
        response = requests.get(
            url,
            headers={"User-Agent": "kiwi-assistant/1.0 (albertogg2@gmail.com)"},
            timeout=_TIMEOUT_S,
        )
        response.raise_for_status()
        payload = response.json()
    except (requests.RequestException, ValueError) as exc:
        log.info("factoid refresh failed: %s: %s", type(exc).__name__, exc)
        return None

    events = payload.get("events") or []
    if not isinstance(events, list) or not events:
        return None

    # Elegimos "el más antiguo" — suele ser el evento histórico más
    # icónico (guerras, tratados, batallas famosas) frente a los
    # aniversarios recientes de series de TV o similares. Determinista
    # por día ⇒ el widget no baila entre refreshes.
    def sort_key(ev: object) -> int:
        if not isinstance(ev, dict):
            return 9999
        year = ev.get("year")
        try:
            return int(year) if year is not None else 9999
        except (TypeError, ValueError):
            return 9999

    events_sorted = sorted(events, key=sort_key)
    event = next(
        (
            ev for ev in events_sorted
            if isinstance(ev, dict)
            and isinstance(ev.get("text"), str)
            and ev.get("text", "").strip()
        ),
        None,
    )
    if event is None:
        return None

    try:
        year = int(event.get("year"))
    except (TypeError, ValueError):
        return None
    return Factoid(
        date_iso=today.isoformat(),
        year=year,
        text=str(event.get("text")).strip(),
    )
