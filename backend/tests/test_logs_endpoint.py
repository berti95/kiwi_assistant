"""Tests for the /api/logs endpoint that receives tablet log batches."""
from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from kiwi_backend.main import app
from kiwi_backend.settings import settings

API_KEY = "kwi_test_secret"


@pytest.fixture(autouse=True)
def _configure_auth(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", API_KEY)


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def _batch(**overrides) -> dict:
    """Helper to assemble a minimum valid LogBatch payload."""
    payload = {
        "device_id": "kiwi-tablet",
        "version_code": 7,
        "version_name": "0.7.0",
        "entries": [
            {
                "ts_ms": 1_700_000_000_000,
                "level": "I",
                "tag": "KiwiViewModel",
                "message": "wake word fired → opening session",
            },
        ],
    }
    payload.update(overrides)
    return payload


def test_post_logs_rejects_missing_api_key(client: TestClient) -> None:
    response = client.post("/api/logs", json=_batch())
    assert response.status_code == 403


def test_post_logs_rejects_wrong_api_key(client: TestClient) -> None:
    response = client.post(
        "/api/logs",
        headers={"X-API-Key": "wrong"},
        json=_batch(),
    )
    assert response.status_code == 403


def test_post_logs_accepts_valid_batch(
    client: TestClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="kiwi_backend.tablet")
    response = client.post(
        "/api/logs",
        headers={"X-API-Key": API_KEY},
        json=_batch(),
    )
    assert response.status_code == 200
    assert response.json() == {"received": 1}
    # Tablet log lands in the dedicated logger.
    assert any(
        "KiwiViewModel" in record.message and "wake word fired" in record.message
        for record in caplog.records
    )


def test_post_logs_accepts_multiple_entries_and_error_field(
    client: TestClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="kiwi_backend.tablet")
    payload = _batch(
        entries=[
            {
                "ts_ms": 1,
                "level": "I",
                "tag": "A",
                "message": "first",
            },
            {
                "ts_ms": 2,
                "level": "W",
                "tag": "B",
                "message": "second",
                "error": "java.lang.RuntimeException: kaboom\n  at ...",
            },
        ],
    )
    response = client.post(
        "/api/logs",
        headers={"X-API-Key": API_KEY},
        json=payload,
    )
    assert response.status_code == 200
    assert response.json() == {"received": 2}
    text = "\n".join(r.message for r in caplog.records)
    assert "first" in text
    assert "second" in text
    assert "kaboom" in text


def test_post_logs_rejects_malformed_payload(client: TestClient) -> None:
    # Missing the "entries" key entirely.
    response = client.post(
        "/api/logs",
        headers={"X-API-Key": API_KEY},
        json={"device_id": "x"},
    )
    assert response.status_code == 422
