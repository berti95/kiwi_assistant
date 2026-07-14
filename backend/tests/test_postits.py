"""Tests para el módulo postits + endpoints REST + tools de voz."""
from __future__ import annotations

import asyncio
from typing import Any

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import postits, state_store, weather
from kiwi_backend.main import app
from kiwi_backend.settings import settings
from kiwi_backend.tools import postit_tools

DEV_TOKEN = "dev-token-test"


def _run(coro):
    return asyncio.run(coro)


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    blobs: dict[str, Any] = {}
    monkeypatch.setattr(
        state_store, "read_json",
        lambda path, default: blobs.get(path, default),
    )
    monkeypatch.setattr(
        state_store, "write_json",
        lambda path, payload: blobs.__setitem__(path, payload),
    )
    return blobs


@pytest.fixture(autouse=True)
def _stub_weather(monkeypatch: pytest.MonkeyPatch) -> None:
    """Impide llamadas reales a Open-Meteo desde tests que golpean /api/home."""
    weather.reset_cache()
    monkeypatch.setattr(weather, "current", lambda: None)
    monkeypatch.setattr(weather, "forecast", lambda date_iso: None)  # noqa: ARG005


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


# ---- Módulo puro ----------------------------------------------------


def test_add_returns_item_with_default_color() -> None:
    item = postits.add("wifi vecino")
    assert item.text == "wifi vecino"
    assert item.color == "yellow"


def test_add_rejects_empty_text() -> None:
    with pytest.raises(ValueError):
        postits.add("   ")


def test_add_falls_back_to_default_on_bad_color() -> None:
    item = postits.add("nota", color="magenta")
    assert item.color == "yellow"


def test_add_accepts_allowed_color() -> None:
    item = postits.add("nota", color="green")
    assert item.color == "green"


def test_list_all_orders_by_recency(monkeypatch: pytest.MonkeyPatch) -> None:
    """Los adds pueden caer en el mismo ms; forzamos timestamps
    monotónicos para verificar el orden 'más reciente primero'."""
    counter = {"n": 0}

    def _tick() -> float:
        counter["n"] += 1
        return float(counter["n"])

    monkeypatch.setattr(postits.time, "time", _tick)
    postits.add("primera")
    postits.add("segunda")
    postits.add("tercera")
    texts = [it.text for it in postits.list_all()]
    assert texts == ["tercera", "segunda", "primera"]


def test_remove_by_id() -> None:
    item = postits.add("borrable")
    postits.remove(item.id)
    assert postits.list_all() == []


def test_remove_by_fuzzy_text() -> None:
    postits.add("codigo del wifi")
    postits.remove("wifi")
    assert postits.list_all() == []


def test_remove_unknown_raises() -> None:
    with pytest.raises(postits.PostItNotFoundError):
        postits.remove("nothing")


def test_clear_returns_count() -> None:
    postits.add("uno")
    postits.add("dos")
    assert postits.clear() == 2
    assert postits.list_all() == []


# ---- REST endpoints -------------------------------------------------


def test_list_endpoint_requires_token(client: TestClient) -> None:
    assert client.get("/api/postits").status_code == 403


def test_list_endpoint_returns_items(client: TestClient) -> None:
    postits.add("wifi vecino", color="blue")
    body = client.get(f"/api/postits?token={DEV_TOKEN}").json()
    assert len(body["items"]) == 1
    assert body["items"][0]["text"] == "wifi vecino"
    assert body["items"][0]["color"] == "blue"


def test_add_endpoint_creates_item(client: TestClient) -> None:
    response = client.post(
        f"/api/postits?token={DEV_TOKEN}",
        json={"text": "llaves cajón azul", "color": "pink"},
    )
    assert response.status_code == 200
    items = response.json()["items"]
    assert items[0]["text"] == "llaves cajón azul"
    assert items[0]["color"] == "pink"


def test_add_endpoint_defaults_color_when_omitted(client: TestClient) -> None:
    response = client.post(
        f"/api/postits?token={DEV_TOKEN}",
        json={"text": "nota"},
    )
    assert response.json()["items"][0]["color"] == "yellow"


def test_add_endpoint_rejects_empty_text(client: TestClient) -> None:
    response = client.post(
        f"/api/postits?token={DEV_TOKEN}",
        json={"text": ""},
    )
    assert response.status_code == 422  # Field validation kicks in


def test_remove_endpoint_drops_item(client: TestClient) -> None:
    item = postits.add("borrable")
    response = client.post(
        f"/api/postits/{item.id}/remove?token={DEV_TOKEN}",
    )
    assert response.status_code == 200
    assert response.json()["items"] == []


def test_remove_endpoint_unknown_returns_404(client: TestClient) -> None:
    response = client.post(f"/api/postits/phantom/remove?token={DEV_TOKEN}")
    assert response.status_code == 404


def test_clear_endpoint(client: TestClient) -> None:
    postits.add("a")
    postits.add("b")
    body = client.post(f"/api/postits/clear?token={DEV_TOKEN}").json()
    assert body == {"cleared": 2, "items": []}


def test_home_endpoint_includes_postits(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """La home devuelve hasta MAX_HOME_POSTITS post-its en el bloque
    dedicado — es lo que alimenta el widget (#12) del home."""
    from kiwi_backend import tools

    monkeypatch.setattr(
        tools.google_auth, "credentials",
        lambda: (_ for _ in ()).throw(
            tools.google_auth.GoogleAuthUnavailableError("no google"),
        ),
    )
    monkeypatch.setattr(
        tools, "_spotify_currently_playing_blocking",
        lambda: ({"playing": False, "track": None}, None),
    )
    monkeypatch.setattr(
        tools, "_spotify_recently_played_blocking",
        lambda limit: {"count": 0, "items": []},  # noqa: ARG005
    )
    counter = {"n": 0}

    def _tick() -> float:
        counter["n"] += 1
        return float(counter["n"])

    monkeypatch.setattr(postits.time, "time", _tick)
    postits.add("wifi", color="blue")
    postits.add("llaves")
    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert len(body["postits"]) == 2
    assert body["postits"][0]["text"] == "llaves"  # más reciente primero


# ---- Voice tools ----------------------------------------------------


def test_postit_add_tool_registers_item() -> None:
    result = _run(postit_tools._postit_add("wifi", "blue"))
    assert "added" in result.response
    assert result.response["added"]["text"] == "wifi"
    assert postits.list_all()[0].color == "blue"


def test_postit_add_tool_missing_text_returns_error() -> None:
    result = _run(postit_tools._postit_add(""))
    assert "error" in result.response


def test_postit_add_tool_bad_color_falls_back() -> None:
    result = _run(postit_tools._postit_add("x", "banana"))
    assert result.response["added"]["color"] == "yellow"


def test_postit_remove_tool_by_fuzzy_text() -> None:
    postits.add("codigo bancario 42")
    result = _run(postit_tools._postit_remove("bancario"))
    assert "removed" in result.response
    assert postits.list_all() == []


def test_postit_remove_tool_unknown_returns_error() -> None:
    result = _run(postit_tools._postit_remove("phantom"))
    assert "error" in result.response


def test_postit_list_tool_returns_all() -> None:
    postits.add("nota-1")
    postits.add("nota-2")
    result = _run(postit_tools._postit_list())
    assert result.response["count"] == 2
