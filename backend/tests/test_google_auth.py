"""Tests for the Google credentials loader.

We don't try to talk to Google here — that needs a real refresh
token. Instead we exercise the parsing / validation / persistence
path: missing env var, malformed JSON, missing fields, refresh
failure, GCS override, save_bundle.
"""
from __future__ import annotations

import json
from typing import Any

import pytest

from kiwi_backend import google_auth, state_store


@pytest.fixture(autouse=True)
def _reset_cache() -> None:
    """Make sure tests see a fresh `Credentials` each time."""
    google_auth.reset_cache()
    yield
    google_auth.reset_cache()


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Replace state_store's read/write with an in-memory blob per test."""
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


def _good_bundle() -> dict[str, Any]:
    return {
        "refresh_token": "rt",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://oauth2.googleapis.com/token",
        "scopes": ["https://www.googleapis.com/auth/calendar.readonly"],
    }


def test_missing_env_var_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(google_auth.ENV_VAR, raising=False)
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="is empty"):
        google_auth.credentials()


def test_malformed_json_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(google_auth.ENV_VAR, "not-json")
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="not valid JSON"):
        google_auth.credentials()


def test_missing_fields_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    bundle = {
        "refresh_token": "rt",
        # missing client_id, client_secret, token_uri
    }
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(bundle))
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="missing fields"):
        google_auth.credentials()


def test_refresh_failure_is_wrapped(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(_good_bundle()))

    def boom(self, request) -> None:  # noqa: ARG001
        raise RuntimeError("network down")

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        boom,
    )
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="refresh failed"):
        google_auth.credentials()
    # Failure surfaced for the home endpoint to display.
    assert google_auth.last_error() is not None
    assert "refresh failed" in google_auth.last_error()  # type: ignore[operator]


def test_successful_refresh_caches_credentials(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(_good_bundle()))

    refresh_calls: list[None] = []

    def fake_refresh(self, request) -> None:  # noqa: ARG001
        refresh_calls.append(None)
        self.token = "fake-access-token"  # noqa: S105

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        fake_refresh,
    )

    first = google_auth.credentials()
    second = google_auth.credentials()

    assert first is second  # cached
    assert first.token == "fake-access-token"
    assert first.refresh_token == "rt"
    assert first.client_id == "cid"
    assert first.scopes == _good_bundle()["scopes"]
    assert len(refresh_calls) == 1  # cached → only refreshed once
    assert google_auth.last_error() is None


def test_gcs_override_wins_over_env_var(
    monkeypatch: pytest.MonkeyPatch,
    _fake_state: dict[str, Any],
) -> None:
    """If a bundle is in GCS the env var is ignored."""
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps({
        **_good_bundle(),
        "refresh_token": "env-token",
    }))
    _fake_state[google_auth.GCS_OVERRIDE_PATH] = {
        **_good_bundle(),
        "refresh_token": "gcs-token",
    }

    def fake_refresh(self, request) -> None:  # noqa: ARG001
        self.token = "ok"  # noqa: S105

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        fake_refresh,
    )

    creds = google_auth.credentials()
    assert creds.refresh_token == "gcs-token"


def test_save_bundle_persists_and_resets_cache(
    monkeypatch: pytest.MonkeyPatch,
    _fake_state: dict[str, Any],
) -> None:
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(_good_bundle()))

    refreshes: list[str] = []

    def fake_refresh(self, request) -> None:  # noqa: ARG001
        refreshes.append(self.refresh_token)
        self.token = "ok"  # noqa: S105

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        fake_refresh,
    )

    # Initial credentials — uses env var.
    first = google_auth.credentials()
    assert first.refresh_token == "rt"

    # Save a new bundle. Cache invalidates and GCS gets written.
    new = {**_good_bundle(), "refresh_token": "new-rt"}
    google_auth.save_bundle(new)
    assert _fake_state[google_auth.GCS_OVERRIDE_PATH]["refresh_token"] == "new-rt"

    # Next credentials() returns the new one.
    second = google_auth.credentials()
    assert second is not first
    assert second.refresh_token == "new-rt"
    assert refreshes == ["rt", "new-rt"]


def test_save_bundle_rejects_incomplete_bundle() -> None:
    with pytest.raises(ValueError, match="missing fields"):
        google_auth.save_bundle({"refresh_token": "rt"})


def test_client_config_reads_from_env_when_no_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(_good_bundle()))
    cfg = google_auth.client_config()
    assert cfg["client_id"] == "cid"
    assert cfg["client_secret"] == "csecret"
    assert cfg["token_uri"] == "https://oauth2.googleapis.com/token"
    assert cfg["scopes"] == _good_bundle()["scopes"]


def test_client_config_raises_when_bundle_missing_client_id(
    monkeypatch: pytest.MonkeyPatch,
    _fake_state: dict[str, Any],
) -> None:
    monkeypatch.delenv(google_auth.ENV_VAR, raising=False)
    _fake_state[google_auth.GCS_OVERRIDE_PATH] = {"refresh_token": "rt"}
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="client config"):
        google_auth.client_config()
