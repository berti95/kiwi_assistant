"""Tests for the Google OAuth web flow endpoints.

We don't talk to Google for real — the exchange call is monkeypatched.
What we want to verify:

- /oauth/google/start requires the dev token and redirects with the
  expected query parameters.
- /oauth/google/callback rejects an unknown state (CSRF defence).
- A valid code → exchange round-trip persists the new bundle via
  google_auth.save_bundle and renders the success page.
- Error paths (Google says "error=access_denied", missing
  refresh_token in the exchange response) render a friendly 4xx/5xx
  HTML instead of crashing.
"""
from __future__ import annotations

import json
from typing import Any
from urllib.parse import parse_qs, urlparse

import pytest
import requests
from fastapi.testclient import TestClient

from kiwi_backend import google_auth, state_store
from kiwi_backend import main as main_module
from kiwi_backend.main import app
from kiwi_backend.settings import settings

DEV_TOKEN = "dev-token-test"


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


@pytest.fixture(autouse=True)
def _reset_google_auth() -> None:
    google_auth.reset_cache()
    yield
    google_auth.reset_cache()


@pytest.fixture(autouse=True)
def _clear_oauth_states() -> None:
    main_module._OAUTH_STATES.clear()
    yield
    main_module._OAUTH_STATES.clear()


def _setup_bundle(monkeypatch: pytest.MonkeyPatch) -> None:
    """Seed an env-var bundle so client_config() returns a usable one."""
    bundle = {
        "refresh_token": "old-rt",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://oauth2.googleapis.com/token",
        "scopes": ["https://www.googleapis.com/auth/calendar.readonly"],
    }
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(bundle))


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_start_requires_dev_token(client: TestClient) -> None:
    response = client.get("/oauth/google/start?token=wrong", follow_redirects=False)
    assert response.status_code == 403


def test_start_redirects_to_google_with_state(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _setup_bundle(monkeypatch)
    response = client.get(
        f"/oauth/google/start?token={DEV_TOKEN}", follow_redirects=False,
    )
    assert response.status_code in (302, 307)
    location = response.headers["location"]
    parsed = urlparse(location)
    assert parsed.netloc == "accounts.google.com"
    query = parse_qs(parsed.query)
    assert query["client_id"] == ["cid"]
    assert query["response_type"] == ["code"]
    assert query["access_type"] == ["offline"]
    assert query["prompt"] == ["consent"]
    assert query["scope"] == ["https://www.googleapis.com/auth/calendar.readonly"]
    assert query["redirect_uri"][0].endswith("/oauth/google/callback")
    # State stored for callback verification.
    state = query["state"][0]
    assert state in main_module._OAUTH_STATES


def test_callback_rejects_unknown_state(client: TestClient) -> None:
    response = client.get(
        "/oauth/google/callback?code=abc&state=never-issued",
    )
    assert response.status_code == 400


def test_callback_renders_google_error(client: TestClient) -> None:
    response = client.get(
        "/oauth/google/callback?error=access_denied",
    )
    assert response.status_code == 400
    assert "access_denied" in response.text


def test_callback_happy_path_saves_bundle(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    _fake_state: dict[str, Any],
) -> None:
    _setup_bundle(monkeypatch)

    # Seed a valid state as if /start had been called.
    state = "test-state"
    main_module._OAUTH_STATES[state] = main_module.time.time()

    # Fake Google's token endpoint response.
    class FakeResponse:
        ok = True
        status_code = 200
        text = ""

        def json(self) -> dict:
            return {
                "refresh_token": "fresh-rt",
                "access_token": "fresh-at",
                "expires_in": 3599,
                "scope": "https://www.googleapis.com/auth/calendar.readonly",
                "token_type": "Bearer",
            }

    def fake_post(url, data, timeout):  # noqa: ARG001
        assert url == "https://oauth2.googleapis.com/token"
        assert data["code"] == "the-code"
        assert data["grant_type"] == "authorization_code"
        return FakeResponse()

    monkeypatch.setattr(requests, "post", fake_post)

    # Stub the eager refresh that happens after save.
    def fake_refresh(self, request) -> None:  # noqa: ARG001
        self.token = "ok"  # noqa: S105

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        fake_refresh,
    )

    response = client.get(
        f"/oauth/google/callback?code=the-code&state={state}",
    )
    assert response.status_code == 200
    assert "Listo" in response.text or "Listo" in response.text
    # Bundle persisted with the new refresh token + cached.
    saved = _fake_state[google_auth.GCS_OVERRIDE_PATH]
    assert saved["refresh_token"] == "fresh-rt"
    assert saved["client_id"] == "cid"


def test_callback_missing_refresh_token_surfaces_error(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _setup_bundle(monkeypatch)
    state = "test-state"
    main_module._OAUTH_STATES[state] = main_module.time.time()

    class FakeResponse:
        ok = True
        status_code = 200
        text = ""

        def json(self) -> dict:
            return {"access_token": "only-access", "expires_in": 3599}

    monkeypatch.setattr(
        requests,
        "post",
        lambda url, data, timeout: FakeResponse(),
    )
    response = client.get(
        f"/oauth/google/callback?code=c&state={state}",
    )
    assert response.status_code == 500
    assert "refresh_token" in response.text.lower()
