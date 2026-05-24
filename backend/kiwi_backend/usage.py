"""Telemetría de uso por conversación.

Cada vez que la WebSocket de una conversación se cierra, gemini.proxy
añade un ``ConversationLog`` aquí con: cuántos turnos hubo, segundos
de audio entrante/saliente y nombres de tools llamadas. El blob vive
en GCS junto al resto del state (``usage.json``).

La agregación por periodo (hoy / últimos 7 días / últimos 30 días)
es in-process al pedir ``/api/stats`` o el tool ``usage_stats``: con
~3000 entradas máx (los registros de >30 días se podan al guardar)
parsear el JSON entero por petición está sobradamente OK.

Coste estimado: producto de los segundos de audio por la tarifa
configurada en settings. Las tarifas por defecto son aproximaciones
del modelo Flash Live actual; cualquiera puede sobrescribirlas con
un env var sin tocar código.
"""
from __future__ import annotations

import logging
import time
import uuid
from collections import Counter
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from typing import Literal

from . import state_store
from .settings import settings

log = logging.getLogger(__name__)

# Si el log de una conversación es más viejo que esto, se descarta al
# guardar la siguiente — evita que el blob crezca para siempre.
_RETENTION_DAYS = 30
_DAY_MS = 24 * 60 * 60 * 1000

Period = Literal["today", "7d", "30d"]


@dataclass(frozen=True)
class ConversationLog:
    id: str
    started_at_ms: int
    ended_at_ms: int
    turn_count: int
    audio_in_seconds: float
    audio_out_seconds: float
    tool_call_names: list[str]


@dataclass
class ProxyStats:
    """Acumulador mutable que [gemini.proxy] llena durante la sesión."""
    turn_count: int = 0
    audio_in_seconds: float = 0.0
    audio_out_seconds: float = 0.0
    tool_call_names: list[str] = field(default_factory=list)


def _path() -> str:
    return settings.kiwi_state_usage_path


def _load_raw() -> list[ConversationLog]:
    raw = state_store.read_json(_path(), default=[])
    if not isinstance(raw, list):
        log.warning("usage.json is not a list (got %s); treating empty", type(raw))
        return []
    out: list[ConversationLog] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            out.append(ConversationLog(
                id=str(entry["id"]),
                started_at_ms=int(entry["started_at_ms"]),
                ended_at_ms=int(entry["ended_at_ms"]),
                turn_count=int(entry.get("turn_count") or 0),
                audio_in_seconds=float(entry.get("audio_in_seconds") or 0.0),
                audio_out_seconds=float(entry.get("audio_out_seconds") or 0.0),
                tool_call_names=list(entry.get("tool_call_names") or []),
            ))
        except (KeyError, ValueError, TypeError):
            log.warning("dropping malformed usage entry: %r", entry)
    return out


def _save(items: list[ConversationLog]) -> None:
    state_store.write_json(_path(), [asdict(it) for it in items])


def log_conversation(
    started_at_ms: int,
    ended_at_ms: int,
    turn_count: int,
    audio_in_seconds: float,
    audio_out_seconds: float,
    tool_call_names: list[str],
) -> ConversationLog:
    """Append a finished conversation to the log + prune old entries."""
    now_ms = int(time.time() * 1000)
    horizon_ms = now_ms - _RETENTION_DAYS * _DAY_MS

    entry = ConversationLog(
        id=uuid.uuid4().hex[:12],
        started_at_ms=int(started_at_ms),
        ended_at_ms=int(ended_at_ms),
        turn_count=int(turn_count),
        audio_in_seconds=round(float(audio_in_seconds), 1),
        audio_out_seconds=round(float(audio_out_seconds), 1),
        tool_call_names=list(tool_call_names),
    )
    items = _load_raw()
    items = [it for it in items if it.started_at_ms >= horizon_ms]
    items.append(entry)
    _save(items)
    return entry


def _period_window_ms(period: Period, now_ms: int | None = None) -> tuple[int, int]:
    """Return [from_ms, to_ms] for a named period.

    Today = desde las 00:00 locales del día actual hasta now. 7d / 30d
    = ventana deslizante exacta (hace exactamente N×24h).
    """
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    if period == "today":
        # Aproximación: 00:00 UTC del día. Para el caso single-user en
        # ES no merece la pena meter la TZ en este corte; los casos
        # extremos (justo a medianoche) se resuelven solos al
        # siguiente refresh.
        seconds_into_day = (now // 1000) % (24 * 3600)
        from_ms = now - seconds_into_day * 1000
        return from_ms, now
    if period == "7d":
        return now - 7 * _DAY_MS, now
    if period == "30d":
        return now - 30 * _DAY_MS, now
    raise ValueError(f"unknown period: {period!r}")


def aggregate(period: Period, now_ms: int | None = None) -> dict:
    """Agregado de uso para mostrar en la pantalla de costes."""
    items = _load_raw()
    from_ms, to_ms = _period_window_ms(period, now_ms=now_ms)
    in_window = [it for it in items if from_ms <= it.started_at_ms <= to_ms]

    audio_in = sum(it.audio_in_seconds for it in in_window)
    audio_out = sum(it.audio_out_seconds for it in in_window)

    tool_counter: Counter[str] = Counter()
    total_turns = 0
    for it in in_window:
        tool_counter.update(it.tool_call_names)
        total_turns += it.turn_count
    top_tools = [
        {"name": name, "count": count}
        for name, count in tool_counter.most_common(8)
    ]

    return {
        "period": period,
        "conversation_count": len(in_window),
        "turn_count": total_turns,
        "audio_in_seconds": round(audio_in, 1),
        "audio_out_seconds": round(audio_out, 1),
        "audio_total_seconds": round(audio_in + audio_out, 1),
        "estimated_cost_eur": round(_cost_eur(audio_in, audio_out, total_turns), 4),
        "top_tools": top_tools,
        "by_day": _by_day(in_window, from_ms, to_ms),
    }


def _cost_eur(audio_in_s: float, audio_out_s: float, turns: int) -> float:
    """Coste estimado en EUR.

    Tres términos: audio entrante, audio saliente y un coste por TURNO.
    El término por turno modela el coste real dominante que el audio
    solo no captura: con la rotación de sesión por turno, cada turno
    re-envía system prompt + historial como tokens de texto de entrada.
    Las constantes están calibradas contra el gasto real observado en
    el billing de Google (ver settings).
    """
    return (
        audio_in_s * settings.kiwi_cost_audio_in_eur_per_second
        + audio_out_s * settings.kiwi_cost_audio_out_eur_per_second
        + turns * settings.kiwi_cost_per_turn_eur
    )


def _by_day(
    in_window: list[ConversationLog], from_ms: int, to_ms: int,
) -> list[dict]:
    """Coste por día (UTC) en la ventana, una entrada por día natural.

    Incluye días sin actividad (coste 0) para que el gráfico de barras
    tenga huecos reales, como el dashboard de facturación de Google.
    """
    cost_by_day: dict[int, float] = {}
    for it in in_window:
        day = it.started_at_ms // _DAY_MS
        cost_by_day[day] = cost_by_day.get(day, 0.0) + _cost_eur(
            it.audio_in_seconds, it.audio_out_seconds, it.turn_count,
        )
    out: list[dict] = []
    for day in range(from_ms // _DAY_MS, to_ms // _DAY_MS + 1):
        label = datetime.fromtimestamp(
            day * _DAY_MS / 1000, tz=UTC,
        ).strftime("%d/%m")
        out.append({"date": label, "cost_eur": round(cost_by_day.get(day, 0.0), 4)})
    return out


def reset_for_tests() -> None:
    """Clears the on-disk log — only for tests."""
    _save([])
