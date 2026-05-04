"""Tests for the Google credentials loader.

We don't try to talk to Google here — that needs a real refresh
token. Instead we exercise the parsing / validation path: missing
env var, malformed JSON, missing fields, refresh failure.
"""
from __future__ import annotations

import json

import pytest

from kiwi_backend import google_auth


@pytest.fixture(autouse=True)
def _reset_cache() -> None:
    """Make sure tests see a fresh `Credentials` each time."""
    google_auth.reset_cache()
    yield
    google_auth.reset_cache()


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


def test_refresh_failure_is_wrapped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bundle = {
        "refresh_token": "rt",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://oauth2.googleapis.com/token",
        "scopes": ["https://www.googleapis.com/auth/calendar.readonly"],
    }
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(bundle))

    def boom(self, request) -> None:  # noqa: ARG001
        raise RuntimeError("network down")

    monkeypatch.setattr(
        "google.oauth2.credentials.Credentials.refresh",
        boom,
    )
    with pytest.raises(google_auth.GoogleAuthUnavailableError, match="refresh failed"):
        google_auth.credentials()


def test_successful_refresh_caches_credentials(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bundle = {
        "refresh_token": "rt",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://oauth2.googleapis.com/token",
        "scopes": ["https://www.googleapis.com/auth/calendar.readonly"],
    }
    monkeypatch.setenv(google_auth.ENV_VAR, json.dumps(bundle))

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
    assert first.scopes == bundle["scopes"]
    assert len(refresh_calls) == 1  # cached → only refreshed once
