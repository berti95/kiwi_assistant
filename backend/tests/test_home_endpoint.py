"""Tests for GET /api/todos and GET /api/home (dev-token gated)."""
from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

from kiwi_backend import state_store, tools, weather
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


# ---- POST /api/todos/{id}/complete + /remove ------------------------


def test_complete_todo_endpoint_marks_item(client: TestClient) -> None:
    from kiwi_backend import todos

    item = todos.add("comprar tomates")
    response = client.post(f"/api/todos/{item.id}/complete?token={DEV_TOKEN}")
    assert response.status_code == 200
    items = response.json()["items"]
    assert len(items) == 1
    assert items[0]["completed"] is True


def test_complete_todo_endpoint_unknown_id_returns_404(client: TestClient) -> None:
    response = client.post(f"/api/todos/phantom/complete?token={DEV_TOKEN}")
    assert response.status_code == 404


def test_complete_todo_endpoint_rejects_missing_token(client: TestClient) -> None:
    from kiwi_backend import todos

    item = todos.add("comprar tomates")
    response = client.post(f"/api/todos/{item.id}/complete")
    assert response.status_code == 403


def test_remove_todo_endpoint_drops_item(client: TestClient) -> None:
    from kiwi_backend import todos

    todos.add("uno")
    item = todos.add("dos")
    response = client.post(f"/api/todos/{item.id}/remove?token={DEV_TOKEN}")
    assert response.status_code == 200
    items = response.json()["items"]
    assert [it["text"] for it in items] == ["uno"]


def test_remove_todo_endpoint_unknown_id_returns_404(client: TestClient) -> None:
    response = client.post(f"/api/todos/phantom/remove?token={DEV_TOKEN}")
    assert response.status_code == 404


# ---- POST /api/timer/cancel -----------------------------------------


def test_cancel_timer_rejects_missing_token(client: TestClient) -> None:
    response = client.post("/api/timer/cancel")
    assert response.status_code == 403


def test_cancel_timer_returns_false_when_none(client: TestClient) -> None:
    from kiwi_backend import timer
    timer.reset()
    body = client.post(f"/api/timer/cancel?token={DEV_TOKEN}").json()
    assert body == {"cancelled": False, "label": ""}


def test_cancel_timer_clears_active_one(client: TestClient) -> None:
    from kiwi_backend import timer
    timer.start(60, label="pasta")
    body = client.post(f"/api/timer/cancel?token={DEV_TOKEN}").json()
    assert body["cancelled"] is True
    assert body["label"] == "pasta"
    assert timer.current() is None
    timer.reset()


# ---- alarms endpoints -----------------------------------------------


def _set_alarm_in_future(label: str = "x") -> str:
    """Helper: schedule an alarm 1 min into the future and return its id."""
    import time as _time

    from kiwi_backend import alarms
    fires = int(_time.time() * 1000) + 60_000
    return alarms.set_alarm(fires, label=label).id


def test_get_home_includes_alarms_list(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)
    _set_alarm_in_future("trabajo")
    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    labels = [a["label"] for a in body["alarms"]]
    assert labels == ["trabajo"]


def test_dismiss_alarm_drops_it(client: TestClient) -> None:
    aid = _set_alarm_in_future("x")
    body = client.post(f"/api/alarms/{aid}/dismiss?token={DEV_TOKEN}").json()
    assert body["dismissed"] is True
    assert body["items"] == []


def test_dismiss_unknown_alarm_idempotent(client: TestClient) -> None:
    body = client.post(f"/api/alarms/phantom/dismiss?token={DEV_TOKEN}").json()
    assert body["dismissed"] is False


def test_snooze_alarm_pushes_fires_at(client: TestClient) -> None:
    import time as _time
    aid = _set_alarm_in_future("x")
    body = client.post(
        f"/api/alarms/{aid}/snooze?token={DEV_TOKEN}&minutes=10",
    ).json()
    assert body["snoozed"] is True
    assert body["fires_at_ms"] >= int(_time.time() * 1000) + 9 * 60_000


def test_snooze_unknown_alarm_returns_404(client: TestClient) -> None:
    response = client.post(f"/api/alarms/phantom/snooze?token={DEV_TOKEN}")
    assert response.status_code == 404


def test_snooze_rejects_zero_minutes(client: TestClient) -> None:
    aid = _set_alarm_in_future("x")
    response = client.post(
        f"/api/alarms/{aid}/snooze?token={DEV_TOKEN}&minutes=0",
    )
    assert response.status_code == 400


def test_alarms_endpoints_require_dev_token(client: TestClient) -> None:
    aid = _set_alarm_in_future("x")
    assert client.post(f"/api/alarms/{aid}/dismiss").status_code == 403
    assert client.post(f"/api/alarms/{aid}/snooze").status_code == 403


# ---- shopping endpoints ---------------------------------------------


def test_get_shopping_returns_empty_initially(client: TestClient) -> None:
    body = client.get(f"/api/shopping?token={DEV_TOKEN}").json()
    assert body == {"items": []}


def test_get_shopping_lists_items(client: TestClient) -> None:
    from kiwi_backend import shopping
    shopping.add("leche")
    shopping.add("pan")
    body = client.get(f"/api/shopping?token={DEV_TOKEN}").json()
    assert [it["text"] for it in body["items"]] == ["leche", "pan"]


def test_complete_shopping_endpoint(client: TestClient) -> None:
    from kiwi_backend import shopping
    item = shopping.add("leche")
    body = client.post(
        f"/api/shopping/{item.id}/complete?token={DEV_TOKEN}",
    ).json()
    assert body["items"][0]["completed"] is True


def test_complete_shopping_unknown_returns_404(client: TestClient) -> None:
    response = client.post(f"/api/shopping/phantom/complete?token={DEV_TOKEN}")
    assert response.status_code == 404


def test_remove_shopping_endpoint(client: TestClient) -> None:
    from kiwi_backend import shopping
    shopping.add("uno")
    item = shopping.add("dos")
    body = client.post(
        f"/api/shopping/{item.id}/remove?token={DEV_TOKEN}",
    ).json()
    assert [it["text"] for it in body["items"]] == ["uno"]


def test_clear_shopping_endpoint(client: TestClient) -> None:
    from kiwi_backend import shopping
    shopping.add("a")
    shopping.add("b")
    body = client.post(f"/api/shopping/clear?token={DEV_TOKEN}").json()
    assert body == {"cleared": 2, "items": []}


def test_shopping_endpoints_require_dev_token(client: TestClient) -> None:
    assert client.get("/api/shopping").status_code == 403
    assert client.post("/api/shopping/x/complete").status_code == 403
    assert client.post("/api/shopping/x/remove").status_code == 403
    assert client.post("/api/shopping/clear").status_code == 403


# ---- /api/stats -----------------------------------------------------


def test_stats_rejects_missing_token(client: TestClient) -> None:
    assert client.get("/api/stats").status_code == 403


def test_stats_invalid_period_returns_400(client: TestClient) -> None:
    response = client.get(f"/api/stats?period=year&token={DEV_TOKEN}")
    assert response.status_code == 400


def test_stats_today_returns_aggregated_payload(client: TestClient) -> None:
    import time as _time

    from kiwi_backend import usage
    now_ms = int(_time.time() * 1000)
    usage.log_conversation(
        started_at_ms=now_ms, ended_at_ms=now_ms + 5_000,
        turn_count=4, audio_in_seconds=20.0, audio_out_seconds=30.0,
        tool_call_names=["spotify_play", "todo_add"],
    )
    body = client.get(f"/api/stats?period=today&token={DEV_TOKEN}").json()
    assert body["conversation_count"] == 1
    assert body["turn_count"] == 4
    assert body["audio_in_seconds"] == 20.0
    assert body["audio_out_seconds"] == 30.0
    assert body["audio_total_seconds"] == 50.0
    names = [t["name"] for t in body["top_tools"]]
    assert "spotify_play" in names


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

    monkeypatch.setattr(tools.spotify, "_spotify_currently_playing_blocking", fake_blocking)


@pytest.fixture(autouse=True)
def _stub_weather(monkeypatch: pytest.MonkeyPatch) -> None:
    """Default weather stub: cloudy 18°. Individual tests override."""
    weather.reset_cache()
    monkeypatch.setattr(
        weather, "current",
        lambda: weather.CurrentWeather(
            temperature_c=18.0,
            weather_code=2,
            description="Parcialmente nublado",
            icon="partly_cloudy",
        ),
    )


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
    # Calendar failure surfaces so the tablet shows "no disponible"
    # en vez de fingir un día tranquilo.
    assert body["events_today_error"] is not None
    assert "not configured" in body["events_today_error"]
    assert [it["text"] for it in body["todos"]] == ["uno"]
    assert body["now_playing"] is None


def test_get_home_plan_chip_appears_only_on_milestone_days(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """plan_chip aparece sólo si algún plan cae en un día-milestone."""
    from datetime import date, timedelta

    from kiwi_backend import plans

    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)

    # 6 días no es milestone (5 sí, 7 sí, 6 no) → sin chip.
    plans.add("Fuera de milestone", (date.today() + timedelta(days=6)).isoformat())
    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["plan_chip"] is None

    # 5 días sí es milestone → chip.
    plans.add("Cantabria", (date.today() + timedelta(days=5)).isoformat())
    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    chip = body["plan_chip"]
    assert chip is not None
    assert chip["label"] == "Cantabria"
    assert chip["days_until"] == 5


def test_get_home_plan_chip_picks_closest_milestone(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Con varios planes en milestone, gana el más cercano."""
    from datetime import date, timedelta

    from kiwi_backend import plans

    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)

    plans.add("Lejano", (date.today() + timedelta(days=30)).isoformat())
    plans.add("Pronto", (date.today() + timedelta(days=3)).isoformat())
    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["plan_chip"]["label"] == "Pronto"


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


def test_get_home_includes_weather_block(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["weather"] == {
        "temperature_c": 18.0,
        "weather_code": 2,
        "description": "Parcialmente nublado",
        "icon": "partly_cloudy",
    }


def test_get_home_weather_null_when_unavailable(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_calendar_unavailable(monkeypatch)
    _stub_spotify_off(monkeypatch)
    monkeypatch.setattr(weather, "current", lambda: None)

    body = client.get(f"/api/home?token={DEV_TOKEN}").json()
    assert body["weather"] is None


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
