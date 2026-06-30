"""Tests for the spotify_service layer + the REST endpoints in main.py.

El service envuelve los helpers blocking de ``tools.spotify`` y los
expone como funciones async. Los tests monkeypatchean los helpers
blocking (igual que hacen ``test_tools.py``) y verifican que el
service traduce los resultados al formato que esperan los endpoints
HTTP.
"""
from __future__ import annotations

import asyncio
from typing import Any

import pytest
import requests
from fastapi.testclient import TestClient

from kiwi_backend import spotify_auth, spotify_service
from kiwi_backend.main import app
from kiwi_backend.settings import settings
from kiwi_backend.tools import spotify as t

DEV_TOKEN = "dev-token-test"


@pytest.fixture(autouse=True)
def _wire_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "dev_logs_token", DEV_TOKEN)


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def _run(coro):
    return asyncio.run(coro)


# ---- service: read paths ------------------------------------------


def test_current_state_returns_snapshot(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {
            "available": True, "playing": True,
            "track": {"uri": "spotify:track:x", "title": "X"},
            "device": {"id": "d1", "name": "Sala", "is_active": True},
        },
    )
    snapshot = _run(spotify_service.current_state())
    assert snapshot["available"] is True
    assert snapshot["track"]["uri"] == "spotify:track:x"


def test_list_devices_returns_normalized(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_devices_blocking",
        lambda: [
            {"id": "a", "name": "Móvil", "type": "Smartphone",
             "is_active": True, "volume_percent": 60,
             "supports_volume": True, "is_restricted": False},
        ],
    )
    response = _run(spotify_service.list_devices())
    assert response["devices"][0]["type"] == "smartphone"
    assert response["devices"][0]["is_active"] is True


def test_search_validates_kind(monkeypatch: pytest.MonkeyPatch) -> None:
    result = _run(spotify_service.search("aitana", "garbage"))
    assert "error" in result
    assert result["status"] == 400


def test_search_calls_blocking(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict = {}

    def fake(query: str, kind: str, limit: int) -> dict:
        captured["query"] = query
        captured["kind"] = kind
        captured["limit"] = limit
        return {"kind": kind, "items": [{"uri": "x"}], "count": 1}

    monkeypatch.setattr(t, "_spotify_search_blocking", fake)
    result = _run(spotify_service.search("aitana", "track", 5))
    assert captured == {"query": "aitana", "kind": "track", "limit": 5}
    assert result["count"] == 1


# ---- service: write paths -----------------------------------------


def test_play_propagates_no_device_as_409(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake(uri, query, device_id=None):  # noqa: ARG001
        return ({"error": "no active spotify device"}, None)

    monkeypatch.setattr(t, "_spotify_play_blocking", fake)
    result = _run(spotify_service.play(query="x"))
    assert "error" in result
    assert result["status"] == 409


def test_play_returns_payload_on_success(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_play_blocking",
        lambda uri, query, device_id=None: (  # noqa: ARG005
            {"started": "spotify:track:x", "track": None}, None,
        ),
    )
    result = _run(spotify_service.play(query="x"))
    assert result["started"] == "spotify:track:x"


def test_seek_clamps_negative(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict = {}

    def fake(position_ms: int) -> dict:
        captured["pos"] = position_ms
        return {"ok": True, "position_ms": position_ms}

    monkeypatch.setattr(t, "_spotify_seek_blocking", fake)
    _run(spotify_service.seek(-500))
    assert captured["pos"] == 0


def test_shuffle_passes_bool(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_shuffle_blocking",
        lambda enabled: {"ok": True, "shuffle": enabled},
    )
    assert _run(spotify_service.shuffle(True))["shuffle"] is True
    assert _run(spotify_service.shuffle(False))["shuffle"] is False


def test_repeat_rejects_unknown_state(monkeypatch: pytest.MonkeyPatch) -> None:
    # No monkeypatch — el blocking checks por su cuenta y devuelve error.
    result = _run(spotify_service.repeat("loop"))
    assert "error" in result


def test_volume_clamps_to_0_100(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[int] = []
    monkeypatch.setattr(
        t, "_spotify_volume_blocking",
        lambda v: captured.append(v) or {"ok": True, "volume_percent": v},
    )
    _run(spotify_service.set_volume(150))
    _run(spotify_service.set_volume(-20))
    assert captured == [100, 0]


def test_transfer_to_name_fuzzy_matches(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_devices_blocking",
        lambda: [
            {"id": "a", "name": "Pixel 9"},
            {"id": "b", "name": "Sonos Salón"},
        ],
    )

    captured: dict = {}

    def fake_transfer(device_id: str):
        captured["id"] = device_id
        return {"transferred_device_id": device_id}, None

    monkeypatch.setattr(t, "_spotify_transfer_blocking", fake_transfer)
    result = _run(spotify_service.transfer_to_name("salón"))
    assert captured["id"] == "b"
    assert result["device_name"] == "Sonos Salón"


def test_transfer_to_name_unknown_returns_404(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_devices_blocking",
        lambda: [{"id": "a", "name": "Pixel 9"}],
    )
    result = _run(spotify_service.transfer_to_name("fantasma"))
    assert "error" in result
    assert result["status"] == 404


def test_like_resolves_active_track(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"track": {"uri": "spotify:track:abc"}},
    )
    captured: dict = {}

    def fake(uri):
        captured["uri"] = uri
        return {"ok": True, "liked": True, "track_id": "abc"}

    monkeypatch.setattr(t, "_spotify_like_blocking", fake)
    result = _run(spotify_service.like())
    assert result["liked"] is True


def test_add_to_queue_resolves_query(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_try_resolve_first_track",
        lambda query: {"uri": "spotify:track:abc"},  # noqa: ARG005
    )
    captured: list[str] = []
    monkeypatch.setattr(
        t, "_spotify_add_to_queue_blocking",
        lambda uri: captured.append(uri) or {"ok": True, "queued": uri},
    )
    _run(spotify_service.add_to_queue(query="suburbia"))
    assert captured == ["spotify:track:abc"]


def test_add_to_queue_missing_args_400(monkeypatch: pytest.MonkeyPatch) -> None:
    result = _run(spotify_service.add_to_queue())
    assert result["status"] == 400


# ---- service: error classification --------------------------------


def test_classify_premium_required() -> None:
    result = spotify_service._classify(t.SpotifyPremiumRequiredError())
    assert result["status"] == 402


def test_classify_rate_limit_passes_retry_after() -> None:
    result = spotify_service._classify(t.SpotifyRateLimitedError(7))
    assert result["status"] == 429
    assert result["retry_after"] == 7


def test_classify_no_device() -> None:
    result = spotify_service._classify(t.SpotifyNoDeviceError())
    assert result["status"] == 409


def test_classify_auth_unavailable() -> None:
    result = spotify_service._classify(
        spotify_auth.SpotifyAuthUnavailableError("test missing"),
    )
    assert result["status"] == 503


def test_classify_generic_http_error() -> None:
    response = requests.Response()
    response.status_code = 500
    exc = requests.HTTPError(response=response)
    result = spotify_service._classify(exc)
    assert result["status"] == 502
    assert result["spotify_status"] == 500


# ---- REST endpoints -----------------------------------------------


def test_state_endpoint_returns_snapshot(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {
            "available": True, "playing": True,
            "track": {"uri": "spotify:track:x", "title": "X"},
        },
    )
    body = client.get(f"/api/spotify/state?token={DEV_TOKEN}").json()
    assert body["available"] is True
    assert body["track"]["title"] == "X"


def test_state_endpoint_rejects_missing_token(client: TestClient) -> None:
    assert client.get("/api/spotify/state").status_code == 403


def test_devices_endpoint(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_devices_blocking",
        lambda: [{"id": "a", "name": "Sala", "type": "Speaker", "is_active": True}],
    )
    body = client.get(f"/api/spotify/devices?token={DEV_TOKEN}").json()
    assert body["devices"][0]["name"] == "Sala"


def test_play_endpoint_no_device_returns_409(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_play_blocking",
        lambda uri, query, device_id=None: (  # noqa: ARG005
            {"error": "no active spotify device"}, None,
        ),
    )
    response = client.post(
        f"/api/spotify/play?query=x&token={DEV_TOKEN}",
    )
    assert response.status_code == 409


def test_play_endpoint_success(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_play_blocking",
        lambda uri, query, device_id=None: (  # noqa: ARG005
            {"started": "spotify:track:abc"}, None,
        ),
    )
    body = client.post(
        f"/api/spotify/play?query=x&token={DEV_TOKEN}",
    ).json()
    assert body["started"] == "spotify:track:abc"


def test_pause_resume_next_previous_endpoints(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[tuple[str, str]] = []

    def fake_simple(method: str, path: str) -> dict:
        captured.append((method, path))
        return {"ok": True}

    monkeypatch.setattr(t, "_spotify_simple_command", fake_simple)
    for path, expected in [
        ("pause", ("PUT", "/me/player/pause")),
        ("resume", ("PUT", "/me/player/play")),
        ("next", ("POST", "/me/player/next")),
        ("previous", ("POST", "/me/player/previous")),
    ]:
        response = client.post(f"/api/spotify/{path}?token={DEV_TOKEN}")
        assert response.status_code == 200
        assert response.json() == {"ok": True}
    assert captured == [
        ("PUT", "/me/player/pause"),
        ("PUT", "/me/player/play"),
        ("POST", "/me/player/next"),
        ("POST", "/me/player/previous"),
    ]


def test_seek_endpoint(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[int] = []
    monkeypatch.setattr(
        t, "_spotify_seek_blocking",
        lambda pos: captured.append(pos) or {"ok": True, "position_ms": pos},
    )
    response = client.post(
        f"/api/spotify/seek?position_ms=42000&token={DEV_TOKEN}",
    )
    assert response.status_code == 200
    assert captured == [42000]


def test_shuffle_endpoint_default_true(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[bool] = []
    monkeypatch.setattr(
        t, "_spotify_shuffle_blocking",
        lambda enabled: captured.append(enabled) or {"ok": True},
    )
    client.post(f"/api/spotify/shuffle?token={DEV_TOKEN}")  # default True
    client.post(f"/api/spotify/shuffle?enabled=false&token={DEV_TOKEN}")
    assert captured == [True, False]


def test_repeat_endpoint_invalid_state_returns_200_with_error(
    client: TestClient,
) -> None:
    # The blocking helper returns ``{"error": ...}`` for unknown states,
    # which our endpoint surfaces as-is (no HTTP error). The client
    # sees status 200 + ``{"error": "..."}`` — fine for "soft" errors
    # that voice/UI handle.
    response = client.post(f"/api/spotify/repeat?mode=loop&token={DEV_TOKEN}")
    assert response.status_code == 200
    assert "error" in response.json()


def test_volume_endpoint(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[int] = []
    monkeypatch.setattr(
        t, "_spotify_volume_blocking",
        lambda v: captured.append(v) or {"ok": True, "volume_percent": v},
    )
    client.post(f"/api/spotify/volume?percent=33&token={DEV_TOKEN}")
    assert captured == [33]


def test_transfer_endpoint_requires_one_of_device_id_or_target(
    client: TestClient,
) -> None:
    response = client.post(f"/api/spotify/transfer?token={DEV_TOKEN}")
    assert response.status_code == 400


def test_transfer_endpoint_by_device_id(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_transfer_blocking",
        lambda device_id: ({"transferred_device_id": device_id}, None),
    )
    body = client.post(
        f"/api/spotify/transfer?device_id=abc&token={DEV_TOKEN}",
    ).json()
    assert body["transferred_device_id"] == "abc"


def test_like_unlike_endpoints(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_like_blocking",
        lambda uri: {"ok": True, "liked": True, "track_id": "abc"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        t, "_spotify_unlike_blocking",
        lambda uri: {"ok": True, "liked": False, "track_id": "abc"},  # noqa: ARG005
    )
    like = client.post(
        f"/api/spotify/like?uri=spotify:track:abc&token={DEV_TOKEN}",
    ).json()
    assert like["liked"] is True
    unlike = client.post(
        f"/api/spotify/unlike?uri=spotify:track:abc&token={DEV_TOKEN}",
    ).json()
    assert unlike["liked"] is False


def test_queue_get_and_post(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_queue_blocking",
        lambda: {"currently_playing": None, "queue": [], "count": 0},
    )
    monkeypatch.setattr(
        t, "_try_resolve_first_track",
        lambda q: {"uri": "spotify:track:xyz"},  # noqa: ARG005
    )
    captured: list[str] = []
    monkeypatch.setattr(
        t, "_spotify_add_to_queue_blocking",
        lambda uri: captured.append(uri) or {"ok": True, "queued": uri},
    )

    get_body = client.get(f"/api/spotify/queue?token={DEV_TOKEN}").json()
    assert get_body == {"currently_playing": None, "queue": [], "count": 0}

    post = client.post(f"/api/spotify/queue?query=suburbia&token={DEV_TOKEN}")
    assert post.status_code == 200
    assert captured == ["spotify:track:xyz"]


def test_library_endpoints_dispatch(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_my_playlists_blocking",
        lambda limit: {"count": 1, "items": [{"uri": "p1", "title": "A"}]},  # noqa: ARG005
    )
    monkeypatch.setattr(
        t, "_spotify_recently_played_blocking",
        lambda limit: {"count": 1, "items": [{"uri": "t1", "title": "B"}]},  # noqa: ARG005
    )
    monkeypatch.setattr(
        t, "_spotify_liked_songs_blocking",
        lambda limit, offset: {  # noqa: ARG005
            "count": 1, "total": 1, "offset": 0, "items": [{"uri": "t2"}],
        },
    )
    monkeypatch.setattr(
        t, "_spotify_featured_playlists_blocking",
        lambda limit: {"count": 1, "message": "Hechos", "items": [{"uri": "p2"}]},  # noqa: ARG005
    )
    monkeypatch.setattr(
        t, "_spotify_top_tracks_blocking",
        lambda limit: {"count": 1, "items": [{"uri": "t3"}]},  # noqa: ARG005
    )
    monkeypatch.setattr(
        t, "_spotify_top_artists_blocking",
        lambda limit: {"count": 1, "items": [{"uri": "a1"}]},  # noqa: ARG005
    )

    assert client.get(
        f"/api/spotify/library/playlists?token={DEV_TOKEN}",
    ).json()["count"] == 1
    assert client.get(
        f"/api/spotify/library/recently_played?token={DEV_TOKEN}",
    ).json()["items"][0]["uri"] == "t1"
    assert client.get(
        f"/api/spotify/library/liked?token={DEV_TOKEN}",
    ).json()["total"] == 1
    assert client.get(
        f"/api/spotify/library/featured?token={DEV_TOKEN}",
    ).json()["message"] == "Hechos"
    assert client.get(
        f"/api/spotify/library/top_tracks?token={DEV_TOKEN}",
    ).json()["items"][0]["uri"] == "t3"
    assert client.get(
        f"/api/spotify/library/top_artists?token={DEV_TOKEN}",
    ).json()["items"][0]["uri"] == "a1"


def test_playlist_tracks_endpoint(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_playlist_tracks_blocking",
        lambda uri, limit: {  # noqa: ARG005
            "uri": uri, "title": "Mix", "count": 1, "items": [{"uri": "t1"}],
        },
    )
    response = client.get(
        f"/api/spotify/playlist?uri=spotify:playlist:abc&token={DEV_TOKEN}",
    )
    assert response.status_code == 200
    assert response.json()["title"] == "Mix"


def test_playlist_tracks_missing_uri_returns_400(client: TestClient) -> None:
    response = client.get(f"/api/spotify/playlist?token={DEV_TOKEN}")
    assert response.status_code == 400


def test_recommend_endpoint_validates_seeds(client: TestClient) -> None:
    # No seeds, no mood → service returns error 502 default
    # because the blocking helper says "supply at least one seed".
    response = client.get(f"/api/spotify/recommend?token={DEV_TOKEN}")
    assert response.status_code == 502
    assert "seed" in response.json()["detail"]["error"]


def test_recommend_endpoint_dispatches(
    client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict = {}

    def fake(seed_track, seed_artist, seed_genre, mood, limit):
        captured.update(dict(
            seed_track=seed_track, seed_artist=seed_artist,
            seed_genre=seed_genre, mood=mood, limit=limit,
        ))
        return {"count": 0, "items": []}

    monkeypatch.setattr(t, "_spotify_recommend_blocking", fake)
    response = client.get(
        f"/api/spotify/recommend?seed_genre=flamenco&mood=chill&token={DEV_TOKEN}",
    )
    assert response.status_code == 200
    assert captured["seed_genre"] == "flamenco"
    assert captured["mood"] == "chill"


# ---- voice tools (new ones) ---------------------------------------


def test_new_voice_tools_are_registered() -> None:
    from kiwi_backend import tools as kt
    names = set(kt.registered_names())
    for name in (
        "spotify_shuffle", "spotify_repeat", "spotify_seek",
        "spotify_like", "spotify_unlike",
        "spotify_add_to_queue", "spotify_view_queue",
        "spotify_my_playlists", "spotify_recently_played",
        "spotify_liked_songs", "spotify_featured_playlists",
        "spotify_top_tracks", "spotify_top_artists",
        "spotify_recommend", "spotify_play_mix",
    ):
        assert name in names, f"voice tool missing: {name}"


def test_shuffle_tool_dispatches(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: list[bool] = []
    monkeypatch.setattr(
        t, "_spotify_shuffle_blocking",
        lambda enabled: captured.append(enabled) or {"ok": True, "shuffle": enabled},
    )
    from kiwi_backend import tools as kt
    result = _run(kt.dispatch("spotify_shuffle", {"enabled": True}))
    assert captured == [True]
    assert result["shuffle"] is True


def test_recommend_tool_with_no_seed_uses_active_track(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"track": {"uri": "spotify:track:active"}},
    )
    captured: dict = {}

    def fake_recommend(seed_track, seed_artist, seed_genre, mood, limit):
        captured["seed_track"] = seed_track
        return {"count": 0, "items": []}

    monkeypatch.setattr(t, "_spotify_recommend_blocking", fake_recommend)
    from kiwi_backend import tools as kt
    _run(kt.dispatch("spotify_recommend", {"mood": "chill"}))
    assert captured["seed_track"] == "spotify:track:active"


def test_play_mix_fuzzy_resolves_playlist(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_my_playlists_blocking",
        lambda limit: {  # noqa: ARG005
            "count": 1, "items": [{"uri": "spotify:playlist:abc", "title": "Daily Mix 2"}],
        },
    )
    monkeypatch.setattr(
        t, "_spotify_featured_playlists_blocking",
        lambda limit: {"count": 0, "items": []},  # noqa: ARG005
    )
    captured: dict = {}

    def fake_play(uri, query, device_id=None):  # noqa: ARG001
        captured["uri"] = uri
        return ({"started": uri}, None)

    monkeypatch.setattr(t, "_spotify_play_blocking", fake_play)
    from kiwi_backend import tools as kt
    result = _run(kt.dispatch("spotify_play_mix", {"name": "daily mix 2"}))
    assert captured["uri"] == "spotify:playlist:abc"
    assert result["mix_title"] == "Daily Mix 2"


def test_spotify_context_line_includes_active_track(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {
            "available": True, "playing": True,
            "track": {
                "uri": "spotify:track:abc",
                "title": "Heroes",
                "artist": "David Bowie",
                "album": "Heroes",
            },
            "device": {"name": "Sonos Salón"},
            "shuffle": True,
            "repeat_state": "context",
        },
    )
    from kiwi_backend import gemini
    line = gemini._spotify_context_line()
    assert "Heroes" in line
    assert "David Bowie" in line
    assert "Sonos Salón" in line
    assert "shuffle sí" in line
    assert "repeat context" in line
    assert "spotify:track:abc" in line


def test_spotify_context_line_empty_when_paused(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"available": True, "playing": False, "track": None},
    )
    from kiwi_backend import gemini
    assert gemini._spotify_context_line() == ""


def test_spotify_context_line_empty_on_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom() -> dict:
        raise RuntimeError("creds missing")

    monkeypatch.setattr(t, "_spotify_full_state_blocking", boom)
    from kiwi_backend import gemini
    assert gemini._spotify_context_line() == ""


def test_unified_media_pause_uses_spotify_when_active(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"available": True, "playing": True, "track": {"uri": "x"}},
    )
    captured: dict = {}
    monkeypatch.setattr(
        t, "_spotify_simple_command",
        lambda method, path: captured.update({"path": path}) or {"ok": True},
    )
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    from kiwi_backend import tools as kt
    _run(kt.dispatch("media_pause", None, on_scene=sink))
    assert captured["path"] == "/me/player/pause"
    assert pushed == []  # no media_key fallback needed


def test_unified_media_pause_falls_back_to_media_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"available": True, "playing": False, "track": None},
    )
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    from kiwi_backend import tools as kt
    result = _run(kt.dispatch("media_pause", None, on_scene=sink))
    assert result["via"] == "media_key"
    assert pushed[0]["type"] == "device_command"
    assert pushed[0]["command"] == "media_key"
    assert pushed[0]["label"] == "pause"


def test_unified_media_next_prefers_spotify(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        t, "_spotify_full_state_blocking",
        lambda: {"available": True, "playing": True, "track": {"uri": "x"}},
    )
    captured: dict = {}
    monkeypatch.setattr(
        t, "_spotify_simple_command",
        lambda method, path: captured.update({"path": path}) or {"ok": True},
    )
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    from kiwi_backend import tools as kt
    _run(kt.dispatch("media_next", None, on_scene=sink))
    assert captured["path"] == "/me/player/next"
    assert pushed == []


def test_unified_media_tools_registered() -> None:
    from kiwi_backend import tools as kt
    names = kt.registered_names()
    for n in ("media_pause", "media_resume", "media_next", "media_previous"):
        assert n in names


def test_send_classifies_premium_required(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    """Spotify 403 con body PREMIUM_REQUIRED → SpotifyPremiumRequiredError."""
    monkeypatch.setenv("KIWI_SPOTIFY_CREDENTIALS", '{}')
    # Mock token holder to bypass auth.

    class FakeHolder:
        def access_token(self) -> str:
            return "tok"

    from kiwi_backend import spotify_auth
    monkeypatch.setattr(spotify_auth, "token_holder", lambda: FakeHolder())
    requests_mock.put(
        "https://api.spotify.com/v1/me/player/pause",
        status_code=403,
        text='{"error":{"status":403,"reason":"PREMIUM_REQUIRED"}}',
    )
    with pytest.raises(t.SpotifyPremiumRequiredError):
        t._spotify_send("PUT", "/me/player/pause")


def test_send_classifies_rate_limit(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    class FakeHolder:
        def access_token(self) -> str:
            return "tok"

    from kiwi_backend import spotify_auth
    monkeypatch.setattr(spotify_auth, "token_holder", lambda: FakeHolder())
    requests_mock.put(
        "https://api.spotify.com/v1/me/player/pause",
        status_code=429,
        headers={"Retry-After": "7"},
        text="rate limited",
    )
    with pytest.raises(t.SpotifyRateLimitedError) as excinfo:
        t._spotify_send("PUT", "/me/player/pause")
    assert excinfo.value.retry_after == 7


def test_spotify_full_state_includes_liked(
    monkeypatch: pytest.MonkeyPatch, requests_mock,
) -> None:
    """El snapshot completo resuelve ``liked`` via /me/tracks/contains."""
    class FakeHolder:
        def access_token(self) -> str:
            return "tok"

    from kiwi_backend import spotify_auth
    monkeypatch.setattr(spotify_auth, "token_holder", lambda: FakeHolder())
    requests_mock.get(
        "https://api.spotify.com/v1/me/player",
        json={
            "is_playing": True,
            "progress_ms": 1000,
            "item": {
                "uri": "spotify:track:abc",
                "name": "Heroes",
                "artists": [{"name": "David Bowie"}],
                "album": {"name": "Heroes", "images": []},
                "duration_ms": 60000,
            },
            "shuffle_state": False,
            "repeat_state": "off",
            "device": {
                "id": "d", "name": "Phone", "type": "Smartphone",
                "is_active": True, "volume_percent": 60, "supports_volume": True,
            },
        },
    )
    requests_mock.get(
        "https://api.spotify.com/v1/me/tracks/contains?ids=abc",
        json=[True],
    )
    snapshot = t._spotify_full_state_blocking()
    assert snapshot["available"] is True
    assert snapshot["playing"] is True
    assert snapshot["liked"] is True
    assert snapshot["track"]["uri"] == "spotify:track:abc"
    assert snapshot["device"]["name"] == "Phone"


def test_recommend_with_mood_no_seed_uses_fallback_genre(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`mood='chill'` sin seed debe usar 'chill' como seed_genre.

    El bug original: "pon música chill" sin nada sonando fallaba con
    "supply at least one seed". Ahora el mood mapea a un género válido
    de Spotify y arranca la recomendación.
    """
    captured: dict = {}

    def fake_get(path: str, params=None):
        captured["path"] = path
        captured["params"] = dict(params or {})
        return {"tracks": [{"uri": "spotify:track:x", "name": "X"}]}

    monkeypatch.setattr(t, "_spotify_get", fake_get)
    result = t._spotify_recommend_blocking(mood="chill")
    assert captured["params"].get("seed_genres") == "chill"
    assert result["count"] == 1
    assert "genre:chill" in result["seeds"]


def test_recommend_every_mood_has_fallback_genre() -> None:
    """Cada mood declarado debe tener un género de fallback válido."""
    for mood in t._MOOD_PROFILES.keys():
        assert mood in t._MOOD_FALLBACK_GENRES, (
            f"mood {mood!r} sin fallback genre — sin esto, 'pon música "
            f"{mood}' sin nada sonando falla."
        )


def test_play_retries_once_on_502_with_device_id(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Play tras wake-up: si Spotify devuelve 502, reintenta una vez.

    Reproduce el bug visto en producción: tras despertar Spotify, el
    device aparece en /me/player/devices pero el primer play da 502
    porque su backend aún no terminó de inicializar. Un solo retry
    con ~1s de pausa lo arregla.
    """
    calls: list[dict] = []

    def fake_send(method: str, path: str, json_body=None):
        calls.append({"method": method, "path": path})
        if len(calls) == 1:
            # 502 con un response object real para que el except entre.
            response = requests.Response()
            response.status_code = 502
            raise requests.HTTPError(response=response)
        return {}

    # Sleep mock para no perder 1.2s en el test.
    monkeypatch.setattr(t.time, "sleep", lambda *_: None)
    monkeypatch.setattr(t, "_spotify_send", fake_send)
    response, _scene = t._spotify_play_blocking(
        uri="spotify:track:abc", query=None, device_id="dev-1",
    )
    assert len(calls) == 2, "expected retry after 502"
    assert "started" in response


def test_play_does_not_retry_on_502_without_device_id(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Si NO hay device_id (play "normal" sin wake), no reintentamos —
    el 502 viene de otro motivo y reintentar gasta latencia para nada."""
    calls: list[int] = []

    def fake_send(method, path, json_body=None):  # noqa: ARG001
        calls.append(1)
        response = requests.Response()
        response.status_code = 502
        raise requests.HTTPError(response=response)

    monkeypatch.setattr(t.time, "sleep", lambda *_: None)
    monkeypatch.setattr(t, "_spotify_send", fake_send)
    response, _scene = t._spotify_play_blocking(
        uri="spotify:track:abc", query=None, device_id=None,
    )
    assert len(calls) == 1
    assert "error" in response
    assert "502" in response["error"]


def test_voice_search_results_scene_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    """Las nuevas tools que pueblan biblioteca empujan ``spotify_results``."""
    monkeypatch.setattr(
        t, "_spotify_my_playlists_blocking",
        lambda limit: {  # noqa: ARG005
            "count": 2,
            "items": [
                {"uri": "p1", "title": "A"},
                {"uri": "p2", "title": "B"},
            ],
        },
    )
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    from kiwi_backend import tools as kt
    result = _run(kt.dispatch("spotify_my_playlists", None, on_scene=sink))
    assert result["count"] == 2
    assert pushed[0]["type"] == "spotify_results"
    assert pushed[0]["kind"] == "playlist"
    assert len(pushed[0]["items"]) == 2
