"""Tests for tablet-facing Spotify control endpoints.

Los tools internos (``_spotify_pause`` etc.) devuelven un dict que
o bien tiene ``{"ok": True}`` o bien ``{"error": "..."}``. El
endpoint HTTP usado por el tablet **promueve** el segundo caso a
HTTP 502 con ``detail``: sin esa promoción Android leía 200 OK
aunque Spotify hubiera fallado, dejaba el icono en estado optimista
y la música seguía sonando.
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


# ---- happy path -------------------------------------------------------


def test_pause_returns_ok_when_tool_succeeds(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_pause() -> dict[str, object]:
        return {"ok": True}

    monkeypatch.setattr(tools, "_spotify_pause", fake_pause)
    resp = client.post(f"/api/spotify/pause?token={DEV_TOKEN}")
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}


# ---- the bug we're fixing --------------------------------------------


def test_pause_surfaces_no_device_as_http_502(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Reproduce el caso "el pause no va": Spotify dice que no hay
    # device. Antes el endpoint devolvía 200 + body con "error" y el
    # tablet lo trataba como éxito; ahora debe promoverlo a 502 para
    # que la UI pueda mostrar feedback.
    err = "no active spotify device. Abre Spotify en el móvil o PC primero."

    async def fake_pause() -> dict[str, object]:
        return {"error": err}

    monkeypatch.setattr(tools, "_spotify_pause", fake_pause)
    resp = client.post(f"/api/spotify/pause?token={DEV_TOKEN}")
    assert resp.status_code == 502
    assert resp.json() == {"detail": err}


def test_resume_surfaces_error_as_http_502(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_resume() -> dict[str, object]:
        return {"error": "spotify api error: 403"}

    monkeypatch.setattr(tools, "_spotify_resume", fake_resume)
    resp = client.post(f"/api/spotify/resume?token={DEV_TOKEN}")
    assert resp.status_code == 502
    assert resp.json()["detail"] == "spotify api error: 403"


@pytest.mark.parametrize("path", ["next", "previous"])
def test_skip_endpoints_surface_errors(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, path: str,
) -> None:
    async def fake() -> dict[str, object]:
        return {"error": "boom"}

    monkeypatch.setattr(tools, f"_spotify_{path}", fake)
    resp = client.post(f"/api/spotify/{path}?token={DEV_TOKEN}")
    assert resp.status_code == 502


# ---- auth ------------------------------------------------------------


def test_pause_requires_dev_token(client: TestClient) -> None:
    resp = client.post("/api/spotify/pause")
    assert resp.status_code == 403


# ---- auth-broken: distinct surface vs no-device ----------------------


def test_auth_error_surfaces_as_http_401_not_502(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Cuando el refresh_token de Spotify caduca, los tools devuelven
    # ``error_kind == "auth"``. El endpoint HTTP debe promoverlo a
    # 401 (no 502) para que Android distinga: 502 = banner rojo
    # transitorio, 401 = overlay 'Renovar Spotify' que abre el flujo
    # OAuth. Tratarlos igual significaría que el usuario reintenta y
    # reintenta sin saber que tiene que reauth.
    async def fake_pause() -> dict[str, object]:
        return {
            "error": "Mi acceso a Spotify ha caducado…",
            "error_kind": "auth",
        }

    monkeypatch.setattr(tools, "_spotify_pause", fake_pause)
    resp = client.post(f"/api/spotify/pause?token={DEV_TOKEN}")
    assert resp.status_code == 401
    assert "caducado" in resp.json()["detail"]


# ---- home flag --------------------------------------------------------


def test_home_exposes_spotify_auth_required_flag(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    # /api/home debe llevar la flag para que el home pueda pintar el
    # chip 'Renovar Spotify' sin esperar a que el usuario entre en
    # NowPlaying (caso "Spotify roto + sin música" — el chip 'Suena
    # ahora' habría desaparecido y dejaría al usuario sin acceso al
    # botón de reauth).
    from kiwi_backend import spotify_auth

    monkeypatch.setattr(spotify_auth, "auth_required", lambda: True)
    resp = client.get(f"/api/home?token={DEV_TOKEN}")
    assert resp.status_code == 200
    assert resp.json()["spotify_auth_required"] is True


def test_home_omits_flag_when_spotify_healthy(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    from kiwi_backend import spotify_auth

    monkeypatch.setattr(spotify_auth, "auth_required", lambda: False)
    resp = client.get(f"/api/home?token={DEV_TOKEN}")
    assert resp.json()["spotify_auth_required"] is False
