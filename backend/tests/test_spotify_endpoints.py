"""Tests for the tablet-facing Spotify player endpoints.

These back the NowPlaying screen's ⏮ ⏯ ⏭ controls and its status
refresh. All dev-token gated, same as the rest of the tap-driven UI.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import tools
from kiwi_backend.main import app
from kiwi_backend.settings import settings

DEV_TOKEN = "dev-token-test"


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


_SCENE = {
    "type": "now_playing",
    "title": "Song",
    "artist": "Artist",
    "album": "Album",
    "album_art_url": "http://art/x.jpg",
    "is_playing": True,
    "duration_ms": 200_000,
    "progress_ms": 42_000,
}


def _stub_status(monkeypatch: pytest.MonkeyPatch, scene: dict | None) -> None:
    monkeypatch.setattr(
        tools, "_spotify_currently_playing_blocking",
        lambda: ({"playing": bool(scene)}, scene),
    )


# ---- auth gating ----------------------------------------------------


def test_status_rejects_missing_token(client: TestClient) -> None:
    assert client.get("/api/spotify/status").status_code == 403


@pytest.mark.parametrize("action", ["pause", "resume", "next", "previous"])
def test_control_rejects_wrong_token(client: TestClient, action: str) -> None:
    assert client.post(f"/api/spotify/{action}?token=nope").status_code == 403


# ---- status ---------------------------------------------------------


def test_status_returns_flattened_scene(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_status(monkeypatch, _SCENE)
    body = client.get(f"/api/spotify/status?token={DEV_TOKEN}").json()
    assert body == {
        "playing": True,
        "title": "Song",
        "artist": "Artist",
        "album": "Album",
        "album_art_url": "http://art/x.jpg",
        "duration_ms": 200_000,
        "progress_ms": 42_000,
    }


def test_status_nothing_playing(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_status(monkeypatch, None)
    body = client.get(f"/api/spotify/status?token={DEV_TOKEN}").json()
    assert body == {"playing": False}


# ---- control --------------------------------------------------------


@pytest.mark.parametrize(
    "action,method,path",
    [
        ("pause", "PUT", "/me/player/pause"),
        ("resume", "PUT", "/me/player/play"),
        ("next", "POST", "/me/player/next"),
        ("previous", "POST", "/me/player/previous"),
    ],
)
def test_control_runs_command_then_returns_status(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    action: str,
    method: str,
    path: str,
) -> None:
    calls: list[tuple[str, str]] = []

    def fake_simple(m: str, p: str) -> dict:
        calls.append((m, p))
        return {"ok": True}

    monkeypatch.setattr(tools.spotify, "_spotify_simple_command", fake_simple)
    monkeypatch.setattr("kiwi_backend.main.asyncio.sleep", _noop_sleep)
    _stub_status(monkeypatch, _SCENE)

    body = client.post(f"/api/spotify/{action}?token={DEV_TOKEN}").json()
    assert calls == [(method, path)]
    assert body["ok"] is True
    assert body["title"] == "Song"
    assert body["playing"] is True


def test_control_surfaces_error_without_500(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        tools.spotify, "_spotify_simple_command",
        lambda m, p: {"error": "no active spotify device."},
    )
    resp = client.post(f"/api/spotify/pause?token={DEV_TOKEN}")
    assert resp.status_code == 200
    assert resp.json() == {"ok": False, "error": "no active spotify device."}


async def _noop_sleep(_seconds: float) -> None:
    return None
