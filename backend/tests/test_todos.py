"""Tests for the TODO list module.

We swap the GCS-backed read/write helpers for an in-memory dict so the
tests stay hermetic — no buckets, no IAM, no creds.
"""
from __future__ import annotations

from typing import Any

import pytest

from kiwi_backend import state_store, todos


@pytest.fixture(autouse=True)
def _fake_state(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Replace state_store's read/write with an in-memory blob per test."""
    blobs: dict[str, Any] = {}

    def fake_read(path: str, default: Any) -> Any:
        return blobs.get(path, default)

    def fake_write(path: str, payload: Any) -> None:
        blobs[path] = payload

    monkeypatch.setattr(state_store, "read_json", fake_read)
    monkeypatch.setattr(state_store, "write_json", fake_write)
    return blobs


def test_list_all_empty_when_blob_missing() -> None:
    assert todos.list_all() == []


def test_add_returns_item_with_id_and_persists() -> None:
    item = todos.add("comprar tomates")
    assert item.text == "comprar tomates"
    assert item.id
    assert item.completed_ms is None
    listed = todos.list_all()
    assert len(listed) == 1
    assert listed[0].id == item.id


def test_add_strips_whitespace_and_rejects_empty() -> None:
    item = todos.add("  llamar a marta  ")
    assert item.text == "llamar a marta"
    with pytest.raises(ValueError):
        todos.add("   ")


def test_add_preserves_creation_order() -> None:
    todos.add("uno")
    todos.add("dos")
    todos.add("tres")
    texts = [it.text for it in todos.list_all()]
    assert texts == ["uno", "dos", "tres"]


def test_complete_by_text_match_sets_completed_ms() -> None:
    todos.add("comprar tomates")
    todos.add("llamar a marta")
    completed = todos.complete("tomates")
    assert completed.text == "comprar tomates"
    assert completed.completed_ms is not None
    # Listing reflects the completion.
    after = {it.text: it.completed_ms for it in todos.list_all()}
    assert after["comprar tomates"] is not None
    assert after["llamar a marta"] is None


def test_complete_by_id_works() -> None:
    item = todos.add("comprar tomates")
    completed = todos.complete(item.id)
    assert completed.id == item.id
    assert completed.completed_ms is not None


def test_complete_unknown_raises() -> None:
    todos.add("uno")
    with pytest.raises(todos.TodoNotFoundError):
        todos.complete("dos")


def test_complete_ambiguous_raises() -> None:
    todos.add("comprar tomates")
    todos.add("comprar tomates frescos")
    # 'comprar' would match both; the fuzzy resolver returns None and
    # we surface that as not-found rather than guessing.
    with pytest.raises(todos.TodoNotFoundError):
        todos.complete("comprar")


def test_complete_idempotent_for_already_completed() -> None:
    item = todos.add("uno")
    todos.complete(item.id)
    again = todos.complete(item.id)
    assert again.completed_ms is not None
    # Only one item still in the list, still completed.
    listed = todos.list_all()
    assert len(listed) == 1
    assert listed[0].completed_ms is not None


def test_remove_by_text_drops_the_item() -> None:
    todos.add("uno")
    todos.add("dos")
    removed = todos.remove("uno")
    assert removed.text == "uno"
    assert [it.text for it in todos.list_all()] == ["dos"]


def test_remove_by_id_works() -> None:
    item = todos.add("solo")
    todos.remove(item.id)
    assert todos.list_all() == []


def test_remove_unknown_raises() -> None:
    with pytest.raises(todos.TodoNotFoundError):
        todos.remove("phantom")


def test_to_wire_shape() -> None:
    item = todos.add("hola")
    wire = todos.to_wire([item])
    assert wire == [{
        "id": item.id,
        "text": "hola",
        "completed": False,
        "created_ms": item.created_ms,
    }]


def test_to_wire_marks_completed() -> None:
    item = todos.add("uno")
    todos.complete(item.id)
    wire = todos.to_wire(todos.list_all())
    assert wire[0]["completed"] is True


def test_load_skips_malformed_entries(_fake_state: dict[str, Any]) -> None:
    _fake_state["todos.json"] = [
        {"id": "ok", "text": "good", "created_ms": 1, "completed_ms": None},
        {"id": "bad"},  # missing fields
        "definitely not a dict",
    ]
    listed = todos.list_all()
    assert [it.id for it in listed] == ["ok"]


def test_load_handles_non_list_blob(_fake_state: dict[str, Any]) -> None:
    _fake_state["todos.json"] = {"oh": "no"}
    assert todos.list_all() == []


# ---- fuzzy matcher in state_store -------------------------------------


def test_fuzzy_match_one_exact_wins() -> None:
    items = [{"text": "comprar tomates"}, {"text": "comprar tomates frescos"}]
    out = state_store.fuzzy_match_one(items, "Comprar Tomates", key=lambda x: x["text"])
    assert out is not None
    assert out["text"] == "comprar tomates"


def test_fuzzy_match_one_strips_accents() -> None:
    items = [{"text": "café con leche"}]
    out = state_store.fuzzy_match_one(items, "cafe", key=lambda x: x["text"])
    assert out is not None
    assert out["text"] == "café con leche"


def test_fuzzy_match_one_returns_none_when_ambiguous() -> None:
    items = [{"text": "comprar tomates"}, {"text": "comprar pan"}]
    assert state_store.fuzzy_match_one(items, "comprar", key=lambda x: x["text"]) is None


def test_fuzzy_match_one_empty_query_returns_none() -> None:
    assert state_store.fuzzy_match_one([{"text": "a"}], "", key=lambda x: x["text"]) is None
