"""Tests for the function-calling tool registry."""
from __future__ import annotations

import asyncio
import re

from google.genai import types

from kiwi_backend import tools


def _run(coro):
    return asyncio.run(coro)


def test_builtin_get_current_time_is_registered() -> None:
    assert "get_current_time" in tools.registered_names()


def test_gemini_tools_bundles_all_declarations_into_one_tool() -> None:
    bundle = tools.gemini_tools()
    assert len(bundle) == 1
    names = {fd.name for fd in bundle[0].function_declarations}
    assert "get_current_time" in names


def test_dispatch_get_current_time_default_tz() -> None:
    result = _run(tools.dispatch("get_current_time", None))
    assert result["timezone"] == tools.DEFAULT_TIMEZONE
    # ISO 8601 with seconds precision and a TZ offset.
    assert re.match(
        r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$",
        result["iso"],
    )
    assert isinstance(result["human"], str) and result["human"]


def test_dispatch_get_current_time_explicit_tz() -> None:
    result = _run(tools.dispatch("get_current_time", {"timezone": "UTC"}))
    assert result["timezone"] == "UTC"
    assert result["iso"].endswith("+00:00")


def test_dispatch_get_current_time_falls_back_to_utc_on_bogus_tz() -> None:
    result = _run(
        tools.dispatch("get_current_time", {"timezone": "Mars/Olympus_Mons"}),
    )
    assert result["timezone"] == "UTC"


def test_dispatch_unknown_tool_returns_error() -> None:
    result = _run(tools.dispatch("not_a_tool", {}))
    assert "error" in result
    assert "not_a_tool" in result["error"]


def test_dispatch_wraps_handler_exception_as_error() -> None:
    def boom() -> dict[str, str]:
        raise RuntimeError("kaboom")

    tools.register(
        name="_test_boom",
        description="Test-only tool that always raises.",
        parameters=None,
        handler=boom,
    )
    try:
        result = _run(tools.dispatch("_test_boom", None))
        assert result == {"error": "RuntimeError: kaboom"}
    finally:
        tools._REGISTRY.pop("_test_boom", None)


def test_dispatch_awaits_async_handlers() -> None:
    async def async_handler(value: int) -> dict[str, int]:
        return {"doubled": value * 2}

    tools.register(
        name="_test_async_double",
        description="Test-only async handler.",
        parameters=None,
        handler=async_handler,
    )
    try:
        result = _run(tools.dispatch("_test_async_double", {"value": 21}))
        assert result == {"doubled": 42}
    finally:
        tools._REGISTRY.pop("_test_async_double", None)


def test_dispatch_wraps_scalar_results_in_object() -> None:
    tools.register(
        name="_test_scalar",
        description="Test-only scalar return.",
        parameters=None,
        handler=lambda: "hello",
    )
    try:
        result = _run(tools.dispatch("_test_scalar", None))
        assert result == {"result": "hello"}
    finally:
        tools._REGISTRY.pop("_test_scalar", None)


def test_register_overwrites_previous_handler() -> None:
    tools.register(
        name="_test_overwrite",
        description="v1",
        parameters=None,
        handler=lambda: {"v": 1},
    )
    tools.register(
        name="_test_overwrite",
        description="v2",
        parameters=None,
        handler=lambda: {"v": 2},
    )
    try:
        # Only one entry exists in the registry under that name.
        assert sum(
            1 for n in tools.registered_names() if n == "_test_overwrite"
        ) == 1
        assert tools._REGISTRY["_test_overwrite"].declaration.description == "v2"
    finally:
        tools._REGISTRY.pop("_test_overwrite", None)


def test_function_declaration_parameters_are_an_object_schema() -> None:
    decl = tools._REGISTRY["get_current_time"].declaration
    assert decl.parameters is not None
    assert decl.parameters.type == types.Type.OBJECT
    assert "timezone" in (decl.parameters.properties or {})


# ---- ToolResult / scene push ---------------------------------------


def test_tool_result_response_is_returned_to_gemini() -> None:
    tools.register(
        name="_test_tool_result",
        description="Test-only ToolResult handler.",
        parameters=None,
        handler=lambda: tools.ToolResult(
            response={"answer": 42},
            scene={"type": "demo", "x": 1},
        ),
    )
    try:
        result = _run(tools.dispatch("_test_tool_result", None))
        # Without an on_scene sink the scene is silently dropped, but
        # Gemini still gets the response payload.
        assert result == {"answer": 42}
    finally:
        tools._REGISTRY.pop("_test_tool_result", None)


def test_tool_result_pushes_scene_when_sink_provided() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    tools.register(
        name="_test_scene_push",
        description="Test-only scene-pushing handler.",
        parameters=None,
        handler=lambda: tools.ToolResult(
            response={"ok": True},
            scene={"type": "demo", "x": 7},
        ),
    )
    try:
        result = _run(tools.dispatch("_test_scene_push", None, on_scene=sink))
        assert result == {"ok": True}
        assert pushed == [{"type": "demo", "x": 7}]
    finally:
        tools._REGISTRY.pop("_test_scene_push", None)


def test_scene_sink_failure_does_not_break_response() -> None:
    async def boom_sink(scene: dict) -> None:  # noqa: ARG001
        raise RuntimeError("network down")

    tools.register(
        name="_test_scene_sink_boom",
        description="Test-only ToolResult with failing sink.",
        parameters=None,
        handler=lambda: tools.ToolResult(
            response={"ok": True},
            scene={"type": "demo"},
        ),
    )
    try:
        result = _run(
            tools.dispatch("_test_scene_sink_boom", None, on_scene=boom_sink),
        )
        # Sink blowing up still ships a clean response to Gemini.
        assert result == {"ok": True}
    finally:
        tools._REGISTRY.pop("_test_scene_sink_boom", None)


def test_tool_result_with_no_scene_does_not_call_sink() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    tools.register(
        name="_test_no_scene",
        description="Test-only ToolResult without scene.",
        parameters=None,
        handler=lambda: tools.ToolResult(response={"ok": True}, scene=None),
    )
    try:
        result = _run(tools.dispatch("_test_no_scene", None, on_scene=sink))
        assert result == {"ok": True}
        assert pushed == []
    finally:
        tools._REGISTRY.pop("_test_no_scene", None)


# ---- calendar tool --------------------------------------------------


def test_calendar_list_events_is_registered() -> None:
    assert "calendar_list_events" in tools.registered_names()


def test_calendar_list_events_unknown_period_returns_error() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "calendar_list_events",
            {"period": "next_century"},
            on_scene=sink,
        ),
    )
    assert "error" in result
    assert "next_century" in result["error"]
    # No scene push for an invalid period — there's nothing to render.
    assert pushed == []


def test_calendar_list_events_happy_path(monkeypatch) -> None:
    """Mock both the credentials loader and the blocking API call."""
    fake_creds = object()  # opaque sentinel

    def fake_credentials():
        return fake_creds

    monkeypatch.setattr(tools.google_auth, "credentials", fake_credentials)

    def fake_list_blocking(creds, time_min, time_max, max_results):  # noqa: ARG001
        assert creds is fake_creds
        assert max_results == 5
        # Simulate two events: one timed, one all-day.
        return [
            {
                "title": "Standup",
                "starts_at": "2026-05-04T09:00:00+02:00",
                "ends_at": "2026-05-04T09:15:00+02:00",
                "location": "Office",
                "all_day": False,
            },
            {
                "title": "Cumpleaños abuela",
                "starts_at": "2026-05-04",
                "ends_at": "2026-05-05",
                "location": None,
                "all_day": True,
            },
        ]

    monkeypatch.setattr(tools, "_list_events_blocking", fake_list_blocking)

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "calendar_list_events",
            {"period": "today", "max_results": 5},
            on_scene=sink,
        ),
    )
    assert result["count"] == 2
    assert result["period"] == "today"
    assert len(result["events"]) == 2
    assert result["events"][0]["title"] == "Standup"

    # The scene push lands before the response is returned to Gemini.
    assert len(pushed) == 1
    assert pushed[0]["type"] == "calendar"
    assert pushed[0]["period"] == "today"
    assert pushed[0]["events"] == result["events"]


def test_calendar_list_events_event_dto_normalises_missing_fields() -> None:
    raw = {
        "summary": "Comer",
        "start": {"dateTime": "2026-05-04T14:00:00+02:00"},
        "end": {"dateTime": "2026-05-04T15:00:00+02:00"},
        # no location, not all-day
    }
    dto = tools._calendar_event_dto(raw)
    assert dto == {
        "title": "Comer",
        "starts_at": "2026-05-04T14:00:00+02:00",
        "ends_at": "2026-05-04T15:00:00+02:00",
        "location": None,
        "all_day": False,
    }


def test_calendar_list_events_event_dto_handles_all_day_and_no_summary() -> None:
    raw = {
        # no summary
        "start": {"date": "2026-05-04"},
        "end": {"date": "2026-05-05"},
    }
    dto = tools._calendar_event_dto(raw)
    assert dto["title"] == "(sin título)"
    assert dto["all_day"] is True
    assert dto["starts_at"] == "2026-05-04"


# ---- youtube tools --------------------------------------------------


def test_youtube_tools_are_registered() -> None:
    names = tools.registered_names()
    for n in (
        "youtube_search",
        "youtube_my_playlists",
        "youtube_playlist_items",
        "youtube_play",
    ):
        assert n in names


def test_parse_iso8601_duration_short_long_and_live() -> None:
    assert tools._parse_iso8601_duration("PT4M13S") == "4:13"
    assert tools._parse_iso8601_duration("PT1H2M5S") == "1:02:05"
    assert tools._parse_iso8601_duration("PT45S") == "0:45"
    assert tools._parse_iso8601_duration("P0D") is None  # live stream sentinel
    assert tools._parse_iso8601_duration(None) is None
    assert tools._parse_iso8601_duration("garbage") is None


def test_youtube_thumbnail_picks_highest_available() -> None:
    thumbs = {
        "default": {"url": "low.jpg"},
        "medium": {"url": "med.jpg"},
        "high": {"url": "hi.jpg"},
    }
    assert tools._youtube_thumbnail(thumbs) == "hi.jpg"
    # Fallback chain when high is missing
    assert tools._youtube_thumbnail({"default": {"url": "low.jpg"}}) == "low.jpg"
    assert tools._youtube_thumbnail({}) is None
    assert tools._youtube_thumbnail(None) is None


def test_resolve_playlist_id_prefers_exact_then_prefix_then_substring() -> None:
    playlists = [
        {"playlist_id": "p1", "title": "Música clásica"},
        {"playlist_id": "p2", "title": "Para ver"},
        {"playlist_id": "p3", "title": "Recetas para preparar"},
    ]
    # exact (case + accent insensitive)
    assert tools._resolve_playlist_id(playlists, "música clásica") == "p1"
    assert tools._resolve_playlist_id(playlists, "MUSICA CLASICA") == "p1"
    # exact wins over prefix
    assert tools._resolve_playlist_id(playlists, "Para ver") == "p2"
    # prefix wins over substring when there are two matches
    assert tools._resolve_playlist_id(playlists, "Recetas") == "p3"
    # substring fallback
    assert tools._resolve_playlist_id(playlists, "ver") == "p2"
    # no match
    assert tools._resolve_playlist_id(playlists, "no existe") is None
    # empty needle
    assert tools._resolve_playlist_id(playlists, "") is None


def test_youtube_search_happy_path(monkeypatch) -> None:
    fake_creds = object()
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: fake_creds)

    def fake_search_blocking(creds, query, max_results):
        assert creds is fake_creds
        assert query == "paella"
        assert max_results == 5
        return [
            {
                "video_id": "abc123",
                "title": "Cómo hacer paella",
                "channel": "Cocina Española",
                "thumbnail_url": "https://img/abc.jpg",
                "duration": "4:13",
            },
        ]

    monkeypatch.setattr(tools, "_youtube_search_blocking", fake_search_blocking)

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "youtube_search",
            {"query": "paella", "max_results": 5},
            on_scene=sink,
        ),
    )
    assert result["count"] == 1
    assert result["videos"][0]["video_id"] == "abc123"
    # Scene shape matches what the tablet expects.
    assert len(pushed) == 1
    assert pushed[0]["type"] == "video_list"
    assert pushed[0]["title"] == '"paella"'
    assert pushed[0]["videos"] == result["videos"]


def test_youtube_search_caps_max_results() -> None:
    captured: dict = {}

    def fake_search_blocking(creds, query, max_results):  # noqa: ARG001
        captured["max_results"] = max_results
        return []

    import pytest as _pt

    monkeypatch = _pt.MonkeyPatch()
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())
    monkeypatch.setattr(tools, "_youtube_search_blocking", fake_search_blocking)
    try:
        _run(tools.dispatch("youtube_search", {"query": "x", "max_results": 999}))
        assert captured["max_results"] == tools._YT_MAX_RESULTS_HARD_CAP
    finally:
        monkeypatch.undo()


def test_youtube_search_missing_query_errors() -> None:
    result = _run(tools.dispatch("youtube_search", {"query": ""}))
    assert "error" in result


def test_youtube_my_playlists_happy_path(monkeypatch) -> None:
    fake_creds = object()
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: fake_creds)

    def fake_blocking(creds, max_results):  # noqa: ARG001
        return [
            {
                "playlist_id": "p1",
                "title": "Para ver",
                "item_count": 7,
                "thumbnail_url": None,
            },
        ]

    monkeypatch.setattr(tools, "_youtube_my_playlists_blocking", fake_blocking)

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch("youtube_my_playlists", None, on_scene=sink),
    )
    assert result["count"] == 1
    assert result["playlists"][0]["title"] == "Para ver"
    assert pushed[0]["type"] == "playlist_list"


def test_youtube_playlist_items_by_id(monkeypatch) -> None:
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())
    monkeypatch.setattr(
        tools, "_youtube_playlist_items_blocking",
        lambda creds, playlist_id, max_results: [  # noqa: ARG005
            {
                "video_id": "v1",
                "title": "Vid 1",
                "channel": "Chan",
                "thumbnail_url": None,
                "duration": "3:10",
            },
        ],
    )
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "youtube_playlist_items",
            {"playlist_id": "PL_xxx"},
            on_scene=sink,
        ),
    )
    assert result["playlist_id"] == "PL_xxx"
    assert result["count"] == 1
    assert pushed[0]["type"] == "video_list"


def test_youtube_playlist_items_by_name_resolves(monkeypatch) -> None:
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())

    def fake_my_playlists(creds, max_results):  # noqa: ARG001
        return [
            {
                "playlist_id": "PL_para_ver",
                "title": "Para ver",
                "item_count": 3,
                "thumbnail_url": None,
            },
            {
                "playlist_id": "PL_otra",
                "title": "Música",
                "item_count": 12,
                "thumbnail_url": None,
            },
        ]

    captured_id: dict[str, str] = {}

    def fake_items(creds, playlist_id, max_results):  # noqa: ARG001
        captured_id["id"] = playlist_id
        return []

    monkeypatch.setattr(tools, "_youtube_my_playlists_blocking", fake_my_playlists)
    monkeypatch.setattr(tools, "_youtube_playlist_items_blocking", fake_items)

    result = _run(
        tools.dispatch(
            "youtube_playlist_items",
            {"playlist_name": "para ver"},
        ),
    )
    assert captured_id["id"] == "PL_para_ver"
    assert result["title"] == "Para ver"
    assert result["count"] == 0


def test_youtube_playlist_items_unknown_name_returns_error(monkeypatch) -> None:
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())
    monkeypatch.setattr(
        tools, "_youtube_my_playlists_blocking",
        lambda creds, max_results: [],  # noqa: ARG005
    )
    result = _run(
        tools.dispatch(
            "youtube_playlist_items",
            {"playlist_name": "no existe"},
        ),
    )
    assert "error" in result
    assert "no playlist found" in result["error"]


def test_youtube_playlist_items_missing_both_args_errors() -> None:
    result = _run(tools.dispatch("youtube_playlist_items", {}))
    assert "error" in result


def test_youtube_play_pushes_video_player_scene() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "youtube_play",
            {"video_id": "dQw4w9WgXcQ", "title": "Foo", "channel": "Bar"},
            on_scene=sink,
        ),
    )
    assert result["playing"] == "dQw4w9WgXcQ"
    assert pushed == [{
        "type": "video_player",
        "video_id": "dQw4w9WgXcQ",
        "title": "Foo",
        "channel": "Bar",
    }]


def test_youtube_play_strips_whitespace_around_video_id() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    _run(
        tools.dispatch(
            "youtube_play",
            {"video_id": "  abc123  "},
            on_scene=sink,
        ),
    )
    assert pushed[0]["video_id"] == "abc123"


def test_youtube_play_missing_video_id_errors() -> None:
    result = _run(tools.dispatch("youtube_play", {"video_id": ""}))
    assert "error" in result


def test_youtube_watch_later_uses_configured_playlist(monkeypatch) -> None:
    from kiwi_backend.settings import settings as live_settings

    monkeypatch.setattr(
        live_settings, "youtube_watch_later_playlist_id", "PL_test_wl",
    )
    monkeypatch.setattr(tools.google_auth, "credentials", lambda: object())

    captured: dict[str, str] = {}

    def fake_items(creds, playlist_id, max_results):  # noqa: ARG001
        captured["id"] = playlist_id
        return [
            {
                "video_id": "v1",
                "title": "Pendiente 1",
                "channel": "C",
                "thumbnail_url": None,
                "duration": "10:00",
            },
        ]

    monkeypatch.setattr(tools, "_youtube_playlist_items_blocking", fake_items)

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(tools.dispatch("youtube_watch_later", None, on_scene=sink))
    assert captured["id"] == "PL_test_wl"
    assert result["title"] == "Ver más tarde"
    assert result["count"] == 1
    assert pushed[0]["type"] == "video_list"
    assert pushed[0]["title"] == "Ver más tarde"


def test_youtube_watch_later_errors_when_unconfigured(monkeypatch) -> None:
    from kiwi_backend.settings import settings as live_settings

    monkeypatch.setattr(live_settings, "youtube_watch_later_playlist_id", "")
    result = _run(tools.dispatch("youtube_watch_later", None))
    assert "error" in result
    assert "not configured" in result["error"]


def test_youtube_open_default_url() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(tools.dispatch("youtube_open", None, on_scene=sink))
    assert result["opened"] == "https://m.youtube.com"
    assert pushed == [{"type": "browse_youtube", "url": "https://m.youtube.com"}]


def test_youtube_open_explicit_url_passes_through() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "youtube_open",
            {"url": "https://m.youtube.com/feed/subscriptions"},
            on_scene=sink,
        ),
    )
    assert result["opened"] == "https://m.youtube.com/feed/subscriptions"
    assert pushed[0]["url"] == "https://m.youtube.com/feed/subscriptions"


def test_youtube_open_adds_https_when_missing() -> None:
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch(
            "youtube_open",
            {"url": "m.youtube.com/@somechannel"},
            on_scene=sink,
        ),
    )
    assert result["opened"] == "https://m.youtube.com/@somechannel"


# ---- todos tools ----------------------------------------------------


import pytest as _pt  # noqa: E402  — local helper for the fixture below


@_pt.fixture
def fake_todos_blob(monkeypatch: _pt.MonkeyPatch) -> dict:
    """Swap state_store's GCS reads/writes with a per-test in-memory dict."""
    from kiwi_backend import state_store

    blobs: dict = {}

    def fake_read(path: str, default):
        return blobs.get(path, default)

    def fake_write(path: str, payload):
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


def test_todos_tools_are_registered() -> None:
    names = tools.registered_names()
    for n in ("todo_add", "todo_list", "todo_complete", "todo_remove"):
        assert n in names


def test_todo_add_pushes_scene_with_updated_list(fake_todos_blob) -> None:  # noqa: ARG001
    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch("todo_add", {"text": "comprar tomates"}, on_scene=sink),
    )
    assert result["added"]["text"] == "comprar tomates"
    assert result["count"] == 1
    assert pushed[0]["type"] == "todo_list"
    assert pushed[0]["items"][0]["text"] == "comprar tomates"


def test_todo_add_missing_text_returns_error() -> None:
    result = _run(tools.dispatch("todo_add", {"text": "  "}))
    assert "error" in result


def test_todo_list_pushes_full_list(fake_todos_blob) -> None:  # noqa: ARG001
    _run(tools.dispatch("todo_add", {"text": "uno"}))
    _run(tools.dispatch("todo_add", {"text": "dos"}))

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(tools.dispatch("todo_list", None, on_scene=sink))
    assert result["count"] == 2
    assert result["pending"] == 2
    texts = [it["text"] for it in result["items"]]
    assert texts == ["uno", "dos"]
    assert pushed[0]["type"] == "todo_list"
    assert [it["text"] for it in pushed[0]["items"]] == ["uno", "dos"]


def test_todo_complete_marks_item_and_pushes_scene(fake_todos_blob) -> None:  # noqa: ARG001
    _run(tools.dispatch("todo_add", {"text": "comprar tomates"}))
    _run(tools.dispatch("todo_add", {"text": "llamar a marta"}))

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch("todo_complete", {"match": "tomates"}, on_scene=sink),
    )
    assert "completed" in result
    assert result["completed"]["text"] == "comprar tomates"
    items_after = pushed[-1]["items"]
    by_text = {it["text"]: it for it in items_after}
    assert by_text["comprar tomates"]["completed"] is True
    assert by_text["llamar a marta"]["completed"] is False


def test_todo_complete_unknown_returns_error_with_pending_list(
    fake_todos_blob,  # noqa: ARG001
) -> None:
    _run(tools.dispatch("todo_add", {"text": "comprar pan"}))
    result = _run(tools.dispatch("todo_complete", {"match": "tomates"}))
    assert "error" in result
    assert "comprar pan" in result["error"]


def test_todo_remove_drops_item(fake_todos_blob) -> None:  # noqa: ARG001
    _run(tools.dispatch("todo_add", {"text": "borrame"}))
    _run(tools.dispatch("todo_add", {"text": "quedate"}))

    pushed: list[dict] = []

    async def sink(scene: dict) -> None:
        pushed.append(scene)

    result = _run(
        tools.dispatch("todo_remove", {"match": "borrame"}, on_scene=sink),
    )
    assert result["removed"]["text"] == "borrame"
    remaining = [it["text"] for it in pushed[-1]["items"]]
    assert remaining == ["quedate"]


def test_todo_remove_unknown_returns_error(fake_todos_blob) -> None:  # noqa: ARG001
    result = _run(tools.dispatch("todo_remove", {"match": "fantasma"}))
    assert "error" in result
