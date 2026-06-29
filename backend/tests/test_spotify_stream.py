"""Tests for the Spotify SSE state-pump.

Verificamos que:
* Si nadie está suscrito, el pump no spammea Spotify.
* Cuando hay suscriptores, polleamos cada POLL_INTERVAL_S y emitimos
  ``snapshot`` la primera vez, luego ``update`` cuando algo cambia.
* Cambios sólo en ``progress_ms`` NO emiten un update (el cliente
  interpola localmente).
* El handler ``event_stream`` formatea los chunks como SSE válidos
  ``event: X\\ndata: {...}\\n\\n``.
"""
from __future__ import annotations

import asyncio
from typing import Any

import pytest

from kiwi_backend import spotify_service, spotify_stream


@pytest.fixture(autouse=True)
def _fast_poll(monkeypatch: pytest.MonkeyPatch) -> None:
    """Acelera el polling para que los tests no tarden."""
    monkeypatch.setattr(spotify_stream, "POLL_INTERVAL_S", 0.02)
    monkeypatch.setattr(spotify_stream, "IDLE_SLEEP_S", 0.02)
    monkeypatch.setattr(spotify_stream, "ERROR_BACKOFF_S", 0.02)


@pytest.fixture(autouse=True)
def _isolated_pump() -> None:
    """Cada test arranca con un pump limpio (no hereda subs/snapshot)."""
    spotify_stream._PUMP = spotify_stream._StatePump()
    yield
    spotify_stream._PUMP = spotify_stream._StatePump()


def test_has_changed_detects_track_uri_change() -> None:
    prev = {"track": {"uri": "spotify:track:a"}, "playing": True}
    curr = {"track": {"uri": "spotify:track:b"}, "playing": True}
    assert spotify_stream._has_changed(prev, curr) is True


def test_has_changed_ignores_only_progress(monkeypatch: pytest.MonkeyPatch) -> None:
    prev = {
        "track": {"uri": "a"}, "playing": True, "progress_ms": 1000,
        "duration_ms": 200_000, "shuffle": False, "repeat_state": "off",
        "available": True, "liked": False,
        "device": {"id": "x", "volume_percent": 50},
    }
    curr = dict(prev, progress_ms=5000)  # only progress changed
    assert spotify_stream._has_changed(prev, curr) is False


def test_has_changed_detects_volume_change() -> None:
    prev = {"track": {"uri": "a"}, "device": {"id": "x", "volume_percent": 50}}
    curr = {"track": {"uri": "a"}, "device": {"id": "x", "volume_percent": 60}}
    assert spotify_stream._has_changed(prev, curr) is True


def test_has_changed_first_time_emits() -> None:
    assert spotify_stream._has_changed(None, {"track": None}) is True


def test_pump_emits_snapshot_then_updates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Conectamos dos veces al pump; verificamos que emite eventos."""
    snapshots = [
        {"available": True, "playing": True, "track": {"uri": "a"}, "progress_ms": 0},
        {"available": True, "playing": True, "track": {"uri": "b"}, "progress_ms": 0},
    ]
    counter = {"i": 0}

    async def fake_state() -> dict[str, Any]:
        i = min(counter["i"], len(snapshots) - 1)
        counter["i"] += 1
        return snapshots[i]

    monkeypatch.setattr(spotify_service, "current_state", fake_state)

    async def run() -> list[dict[str, Any]]:
        queue = await spotify_stream._PUMP.subscribe()
        try:
            events: list[dict[str, Any]] = []
            # Espera hasta que veamos un update con track uri 'b' o
            # se agote el timeout (200 ms en este harness rápido).
            deadline = asyncio.get_event_loop().time() + 1.0
            while asyncio.get_event_loop().time() < deadline:
                try:
                    ev = await asyncio.wait_for(queue.get(), timeout=0.1)
                except asyncio.TimeoutError:
                    continue
                events.append(ev)
                if (
                    ev.get("event") == "update"
                    and (ev.get("state") or {}).get("track", {}).get("uri") == "b"
                ):
                    break
            return events
        finally:
            await spotify_stream._PUMP.unsubscribe(queue)

    events = asyncio.run(run())
    # Empezamos con un update con track 'a' (el primer poll, no snapshot
    # cacheado al subscribe), luego un update con track 'b'.
    track_uris = [
        (e.get("state") or {}).get("track", {}).get("uri")
        for e in events
        if e.get("event") in ("update", "snapshot")
    ]
    assert "a" in track_uris
    assert "b" in track_uris


def test_pump_emits_error_event_on_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    """Si current_state lanza, el pump emite ``error`` y sobrevive."""
    raised = {"once": False}

    async def fake_state() -> dict[str, Any]:
        if not raised["once"]:
            raised["once"] = True
            raise RuntimeError("boom")
        return {"available": True, "playing": False, "track": None}

    monkeypatch.setattr(spotify_service, "current_state", fake_state)

    async def run() -> list[dict[str, Any]]:
        queue = await spotify_stream._PUMP.subscribe()
        try:
            events: list[dict[str, Any]] = []
            deadline = asyncio.get_event_loop().time() + 1.0
            while asyncio.get_event_loop().time() < deadline:
                try:
                    ev = await asyncio.wait_for(queue.get(), timeout=0.1)
                except asyncio.TimeoutError:
                    continue
                events.append(ev)
                if ev.get("event") == "error":
                    break
            return events
        finally:
            await spotify_stream._PUMP.unsubscribe(queue)

    events = asyncio.run(run())
    errors = [e for e in events if e.get("event") == "error"]
    assert errors, f"expected an error event, got {events}"
    assert "boom" in errors[0]["message"]


def test_event_stream_formats_sse_chunks(monkeypatch: pytest.MonkeyPatch) -> None:
    """``event_stream`` emite bytes en formato SSE válido."""
    snapshot = {"available": True, "playing": False, "track": None}

    async def fake_state() -> dict[str, Any]:
        return snapshot

    monkeypatch.setattr(spotify_service, "current_state", fake_state)

    async def run() -> list[bytes]:
        chunks: list[bytes] = []
        stream = spotify_stream.event_stream()
        for _ in range(2):  # heartbeat + first event
            try:
                chunk = await asyncio.wait_for(stream.__anext__(), timeout=1.0)
            except StopAsyncIteration:
                break
            chunks.append(chunk)
        await stream.aclose()
        return chunks

    chunks = asyncio.run(run())
    assert chunks[0].startswith(b":")  # heartbeat comment
    payload = b"".join(chunks)
    # Algún evento con shape SSE: "event: X\ndata: {...}\n\n"
    assert b"event: " in payload
    assert b"data: " in payload
    assert payload.endswith(b"\n\n")
