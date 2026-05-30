"""Tests for /oauth/spotify/{start,callback}.

Calca el patrón de /oauth/google/* — flujo Authorization Code con
``client_config()`` cargado del bundle existente; el callback
intercambia el code, llama a ``save_bundle()`` y limpia el flag de
auth-broken.
"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import spotify_auth
from kiwi_backend.main import app
from kiwi_backend.settings import settings

DEV_TOKEN = "dev-token-test"


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture(autouse=True)
def _reset_spotify_auth() -> None:
    spotify_auth.reset_cache()
    yield
    spotify_auth.reset_cache()


@pytest.fixture
def _bundle(monkeypatch: pytest.MonkeyPatch) -> dict:
    bundle = {
        "refresh_token": "rt-existing",
        "client_id": "cid-test",
        "client_secret": "csecret-test",
        "token_uri": "https://accounts.spotify.com/api/token",
        "scopes": ["user-modify-playback-state", "user-read-playback-state"],
    }
    monkeypatch.setenv(spotify_auth.ENV_VAR, json.dumps(bundle))
    return bundle


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


# ---- /start ----------------------------------------------------------


def test_start_requires_dev_token(client: TestClient) -> None:
    resp = client.get("/oauth/spotify/start", follow_redirects=False)
    assert resp.status_code == 403


def test_start_redirects_to_spotify_consent(
    client: TestClient, _bundle: dict, requests_mock,
) -> None:
    # Necesitamos que _load_bundle / token_holder no necesite refrescar
    # por nosotros: client_config solo lee el bundle, no refresca.
    resp = client.get(
        f"/oauth/spotify/start?token={DEV_TOKEN}", follow_redirects=False,
    )
    assert resp.status_code in (302, 307)
    location = resp.headers["location"]
    assert location.startswith("https://accounts.spotify.com/authorize?")
    # Parámetros críticos: client_id, scopes, show_dialog para forzar
    # consent en una renovación explícita (sin esto Spotify reusa el
    # grant previo y no garantiza nuevo refresh_token).
    assert "client_id=cid-test" in location
    assert "show_dialog=true" in location
    assert "user-modify-playback-state" in location  # scope ahí
    assert "state=" in location


# ---- /callback -------------------------------------------------------


def test_callback_rejects_unknown_state(client: TestClient, _bundle: dict) -> None:
    resp = client.get(
        f"/oauth/spotify/callback?code=AC&state=unknown-state",
    )
    assert resp.status_code == 400


def test_callback_persists_bundle_and_marks_healthy(
    client: TestClient,
    _bundle: dict,
    requests_mock,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Estado de partida: el holder está "broken" (último refresh falló).
    # Después del callback exitoso el flag tiene que limpiarse.
    spotify_auth._last_error = "previous refresh failed"  # type: ignore[attr-defined]

    # Capturamos el bundle que el callback persiste.
    saved: dict = {}

    def fake_save_bundle(bundle: dict) -> None:
        saved.update(bundle)
        # save_bundle real limpia _last_error; lo simulamos.
        spotify_auth._last_error = None  # type: ignore[attr-defined]

    monkeypatch.setattr(spotify_auth, "save_bundle", fake_save_bundle)

    # Forzamos el holder a no llamar a Spotify de verdad en el primer
    # refresh post-save.
    class FakeHolder:
        def access_token(self) -> str:
            return "at-fake"

    monkeypatch.setattr(spotify_auth, "token_holder", lambda: FakeHolder())

    # Sembramos un state válido directamente en el dict (en producción
    # lo crea /start, aquí lo cortocircuitamos).
    from kiwi_backend import main as main_module
    main_module._OAUTH_STATES["valid-state"] = 1e12  # un futuro lejano

    requests_mock.post(
        "https://accounts.spotify.com/api/token",
        json={
            "access_token": "at-from-callback",
            "refresh_token": "rt-fresh",
            "expires_in": 3600,
        },
    )

    resp = client.get(
        "/oauth/spotify/callback?code=AC-test&state=valid-state",
    )
    assert resp.status_code == 200
    assert "Listo" in resp.text
    # Guardado con el refresh_token nuevo + las credenciales del client.
    assert saved["refresh_token"] == "rt-fresh"
    assert saved["client_id"] == "cid-test"
    assert spotify_auth.auth_required() is False


def test_callback_surfaces_spotify_error(
    client: TestClient, _bundle: dict,
) -> None:
    resp = client.get(
        "/oauth/spotify/callback?error=access_denied&state=whatever",
    )
    assert resp.status_code == 400
    assert "access_denied" in resp.text


def test_callback_handles_no_refresh_token_in_response(
    client: TestClient,
    _bundle: dict,
    requests_mock,
) -> None:
    # Spotify puede devolver access_token sin refresh_token si el
    # cliente no pidió bien los scopes — guardar el bundle a medias
    # nos dejaría peor que antes. Mejor surface error 500.
    from kiwi_backend import main as main_module
    main_module._OAUTH_STATES["s2"] = 1e12

    requests_mock.post(
        "https://accounts.spotify.com/api/token",
        json={"access_token": "at", "expires_in": 3600},  # sin refresh_token
    )
    resp = client.get(
        "/oauth/spotify/callback?code=AC2&state=s2",
    )
    assert resp.status_code == 500
    assert "refresh_token" in resp.text
