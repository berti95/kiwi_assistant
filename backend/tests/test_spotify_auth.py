"""Tests for the Spotify credentials holder."""
from __future__ import annotations

import json

import pytest

from kiwi_backend import spotify_auth


@pytest.fixture(autouse=True)
def _reset_cache() -> None:
    spotify_auth.reset_cache()
    yield
    spotify_auth.reset_cache()


def test_missing_env_var_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(spotify_auth.ENV_VAR, raising=False)
    with pytest.raises(spotify_auth.SpotifyAuthUnavailableError, match="is empty"):
        spotify_auth.token_holder()


def test_malformed_json_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(spotify_auth.ENV_VAR, "not-json")
    with pytest.raises(
        spotify_auth.SpotifyAuthUnavailableError, match="not valid JSON",
    ):
        spotify_auth.token_holder()


def test_missing_fields_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    bundle = {"refresh_token": "rt"}  # no client_id/secret/uri
    monkeypatch.setenv(spotify_auth.ENV_VAR, json.dumps(bundle))
    with pytest.raises(
        spotify_auth.SpotifyAuthUnavailableError, match="missing fields",
    ):
        spotify_auth.token_holder()


def test_successful_refresh_caches_token(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    bundle = {
        "refresh_token": "rt-1",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://accounts.spotify.com/api/token",
        "scopes": ["user-read-playback-state"],
    }
    monkeypatch.setenv(spotify_auth.ENV_VAR, json.dumps(bundle))

    requests_mock.post(
        bundle["token_uri"],
        json={"access_token": "at-fresh", "expires_in": 3600},
    )

    first = spotify_auth.token_holder()
    second = spotify_auth.token_holder()
    assert first is second  # lru_cache
    assert first.access_token() == "at-fresh"
    # Second call inside the leeway window does NOT refresh again.
    assert requests_mock.call_count == 1


def test_refresh_failure_raises(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    bundle = {
        "refresh_token": "rt",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://accounts.spotify.com/api/token",
        "scopes": [],
    }
    monkeypatch.setenv(spotify_auth.ENV_VAR, json.dumps(bundle))
    requests_mock.post(bundle["token_uri"], status_code=400, text="bad refresh")
    with pytest.raises(
        spotify_auth.SpotifyAuthUnavailableError, match="refresh failed",
    ):
        spotify_auth.token_holder()


def test_rotated_refresh_token_is_picked_up(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    bundle = {
        "refresh_token": "rt-old",
        "client_id": "cid",
        "client_secret": "csecret",
        "token_uri": "https://accounts.spotify.com/api/token",
        "scopes": [],
    }
    monkeypatch.setenv(spotify_auth.ENV_VAR, json.dumps(bundle))
    requests_mock.post(
        bundle["token_uri"],
        json={
            "access_token": "at",
            "expires_in": 3600,
            "refresh_token": "rt-new",
        },
    )
    holder = spotify_auth.token_holder()
    assert holder.refresh_token == "rt-new"
