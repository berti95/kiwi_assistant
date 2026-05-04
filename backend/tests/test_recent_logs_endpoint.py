"""Tests for the GET /api/logs/recent dev endpoint + the ring buffer."""
from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import log_buffer
from kiwi_backend.main import app
from kiwi_backend.settings import settings

DEV_TOKEN = "dev-token-test"
API_KEY = "kwi_test_secret"


@pytest.fixture(autouse=True)
def _configure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", API_KEY)
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)
    log_buffer.reset()
    yield
    log_buffer.reset()


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_recent_logs_rejects_missing_token(client: TestClient) -> None:
    response = client.get("/api/logs/recent")
    assert response.status_code == 403


def test_recent_logs_rejects_wrong_token(client: TestClient) -> None:
    response = client.get("/api/logs/recent?token=nope")
    assert response.status_code == 403


def test_recent_logs_returns_empty_when_buffer_clean(client: TestClient) -> None:
    response = client.get(f"/api/logs/recent?token={DEV_TOKEN}")
    assert response.status_code == 200
    body = response.json()
    assert body["entries"] == []
    assert body["count"] == 0
    # Cursor reflects current high-water (zero before anything's logged).
    assert body["next_since"] == 0


def test_recent_logs_captures_backend_log_calls(client: TestClient) -> None:
    log = logging.getLogger("kiwi_backend.test_recent_logs")
    log.warning("hello world")
    log.info("second line")

    response = client.get(f"/api/logs/recent?token={DEV_TOKEN}")
    assert response.status_code == 200
    body = response.json()
    messages = [e["message"] for e in body["entries"]]
    assert "hello world" in messages
    assert "second line" in messages
    # Cursor is monotonically increasing — pass it back to get the tail.
    next_since = body["next_since"]
    assert next_since >= 2


def test_recent_logs_cursor_returns_only_new_entries(client: TestClient) -> None:
    log = logging.getLogger("kiwi_backend.test_cursor")

    log.info("first")
    initial = client.get(f"/api/logs/recent?token={DEV_TOKEN}").json()
    cursor = initial["next_since"]
    assert any(e["message"] == "first" for e in initial["entries"])

    log.info("second")
    log.info("third")
    second = client.get(
        f"/api/logs/recent?token={DEV_TOKEN}&since={cursor}",
    ).json()
    msgs = [e["message"] for e in second["entries"]]
    assert msgs == ["second", "third"]


def test_recent_logs_captures_tablet_post(client: TestClient) -> None:
    """Tablet-shipped lines flow through kiwi_backend.tablet → buffer."""
    response = client.post(
        "/api/logs",
        headers={"X-API-Key": API_KEY},
        json={
            "device_id": "kiwi-tablet",
            "version_code": 1,
            "version_name": "0.1.0",
            "entries": [
                {
                    "ts_ms": 1700000000000,
                    "level": "I",
                    "tag": "KiwiViewModel",
                    "message": "wake word fired",
                },
            ],
        },
    )
    assert response.status_code == 200

    recent = client.get(f"/api/logs/recent?token={DEV_TOKEN}").json()
    msgs = [e["message"] for e in recent["entries"]]
    # The tablet line is wrapped in the tablet logger's prefix; check
    # for the original tag/message combo lives somewhere in the buffer.
    assert any("KiwiViewModel" in m and "wake word fired" in m for m in msgs)


def test_recent_logs_respects_limit(client: TestClient) -> None:
    log = logging.getLogger("kiwi_backend.test_limit")
    for i in range(10):
        log.info(f"line {i}")

    response = client.get(f"/api/logs/recent?token={DEV_TOKEN}&limit=3").json()
    assert response["count"] == 3
    assert [e["message"] for e in response["entries"]] == [
        "line 0",
        "line 1",
        "line 2",
    ]
