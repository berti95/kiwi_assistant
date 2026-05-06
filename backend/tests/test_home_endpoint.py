"""Tests for GET /api/todos and GET /api/home (dev-token gated)."""
from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import state_store, tools
from kiwi_backend.main import app
from kiwi_backend.settings import settings

DEV_TOKEN = "dev-token-test"


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Hermetic state store — no GCS calls."""
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


# ---- /api/todos -----------------------------------------------------


def test_get_todos_rejects_missing_token(client: TestClient) -> None:
    assert client.get("/api/todos").status_code == 403


def test_get_todos_rejects_wrong_token(client: TestClient) -> None:
    assert client.get("/api/todos?token=nope").status_code == 403


def test_get_todos_returns_empty_initially(client: TestClient) -> None:
    response = client.get(f"/api/todos?token={DEV_TOKEN}")
    assert response.status_code == 200
    assert response.json() == {"items": []}


def test_get_todos_returns_added_items(client: TestClient) -> None:
    from kiwi_backend import todos

    todos.add("comprar tomates")
    todos.add("llamar a marta")

    body = client.get(f"/api/todos?token={DEV_TOKEN}").json()
    assert [it["text"] for it in body["items"]] == [
        "comprar tomates",
        "llamar a marta",
    ]
    assert all(it["completed"] is False for it in body["items"])


# ---- /api/home ------------------------------------------------------


def test_get_home_rejects_missing_token(client: TestClient) -> None:
    assert client.get("/api/home").status_code == 403


def _stub_calendar_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    """Make Calendar credentials raise so the calendar branch is empty."""
    def boom() -> None:
        raise tools.google_auth.GoogleAuthUnavailableError("test: not configured")

    monkeypatch.setattr(tools.google_auth, "credentials", boom)


def _stub_spotify_off(monkeypatch: pytest.MonkeyPatch) -> None:
    """Spotify currently_playing returns (response, None) → no chip."""
    def fake_blocking() -> tuple[dict, dict | None]:
        return ({"playing": False, "track": None}, None)

    monkeypatch.setattr(tools, "_spotify_currently_playing_blocking", fake_blocking)


def test_get_home_happy_path_with_todos_and_no_calendar_no_spotify(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from kiwi_backend import todos

    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)

    todos.add("uno")

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["events_today"] == []
    assert [it["text"] for it in body["todos"]] == ["uno"]
    assert body["now_playing"] is None


def test_get_home_includes_today_calendar_events(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())
    monkeypatch.setattr(
        tools, "_list_events_blocking",
        lambda creds, time_min, time_max, max_results: [  # noqa: ARG005
            {
                "title": "Standup",
                "starts_at": "2026-05-04T09:00:00+02:00",
                "ends_at": "2026-05-04T09:15:00+02:00",
                "location": None,
                "all_day": False,
            },
        ],
    )
    _stub_spotify_off(monkeypatch)

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    titles = [e["title"] for e in body["events_today"]]
    assert "Standup" in titles


def test_get_home_includes_now_playing_when_spotify_active(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_calendar_unavailable(monkeypatch)

    def fake_currently_playing() -> tuple[dict, dict | None]:
        return (
            {"playing": True, "track": None},
            {
                "type": "now_playing",
                "title": "Heroes",
                "artist": "David Bowie",
                "album": "Heroes",
                "album_art_url": "https://img/heroes.jpg",
                "is_playing": True,
                "duration_ms": 1000,
                "progress_ms": 0,
            },
        )

    monkeypatch.setattr(
        tools, "_spotify_currently_playing_blocking", fake_currently_playing,
    )

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["now_playing"] == {
        "title": "Heroes",
        "artist": "David Bowie",
        "album_art_url": "https://img/heroes.jpg",
    }


def test_get_home_skips_now_playing_when_spotify_paused(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A paused track has is_playing=False — don't show the chip."""
    _stub_calendar_unavailable(monkeypatch)

    def fake_currently_playing() -> tuple[dict, dict | None]:
        return (
            {"playing": False, "track": None},
            {
                "type": "now_playing",
                "title": "Heroes",
                "artist": "David Bowie",
                "album": "",
                "album_art_url": None,
                "is_playing": False,
                "duration_ms": 0,
                "progress_ms": 0,
            },
        )

    monkeypatch.setattr(
        tools, "_spotify_currently_playing_blocking", fake_currently_playing,
    )

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["now_playing"] is None
