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
