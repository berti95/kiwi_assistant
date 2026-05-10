"""Tests for the shopping list module."""
from __future__ import annotations

from typing import Any

import pytest

from kiwi_backend import shopping, state_store


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


def test_list_all_empty_initially() -> None:
    assert shopping.list_all() == []


def test_add_persists() -> None:
    item = shopping.add("leche")
    assert item.text == "leche"
    listed = shopping.list_all()
    assert len(listed) == 1
    assert listed[0].id == item.id


def test_add_strips_whitespace_and_rejects_empty() -> None:
    item = shopping.add("  pan integral  ")
    assert item.text == "pan integral"
    with pytest.raises(ValueError):
        shopping.add("   ")


def test_complete_marks_item() -> None:
    shopping.add("leche")
    shopping.add("pan")
    completed = shopping.complete("leche")
    assert completed.completed_ms is not None
    by_text = {it.text: it.completed_ms for it in shopping.list_all()}
    assert by_text["leche"] is not None
    assert by_text["pan"] is None


def test_complete_unknown_raises() -> None:
    with pytest.raises(shopping.ShoppingItemNotFoundError):
        shopping.complete("manzana")


def test_remove_drops_item() -> None:
    shopping.add("leche")
    shopping.add("pan")
    shopping.remove("leche")
    assert [it.text for it in shopping.list_all()] == ["pan"]


def test_remove_unknown_raises() -> None:
    with pytest.raises(shopping.ShoppingItemNotFoundError):
        shopping.remove("phantom")


def test_clear_all_returns_count_and_empties() -> None:
    shopping.add("leche")
    shopping.add("pan")
    n = shopping.clear_all()
    assert n == 2
    assert shopping.list_all() == []


def test_clear_when_empty_returns_zero() -> None:
    assert shopping.clear_all() == 0


def test_to_wire_shape() -> None:
    item = shopping.add("leche")
    wire = shopping.to_wire([item])
    assert wire == [{
        "id": item.id,
        "text": "leche",
        "completed": False,
        "created_ms": item.created_ms,
    }]


def test_load_skips_malformed_entries(_fake_state: dict[str, Any]) -> None:
    _fake_state["shopping.json"] = [
        {"id": "ok", "text": "leche", "created_ms": 1, "completed_ms": None},
        {"id": "bad"},  # missing fields
        "not a dict",
    ]
    listed = shopping.list_all()
    assert [it.id for it in listed] == ["ok"]
