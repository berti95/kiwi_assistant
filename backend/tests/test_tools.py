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
