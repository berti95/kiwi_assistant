"""Tests para el módulo plans (viajes / eventos especiales)."""
from __future__ import annotations

from datetime import date, timedelta
from typing import Any

import pytest

from kiwi_backend import plans, state_store


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


def _future(days: int) -> str:
    return (date.today() + timedelta(days=days)).isoformat()


def test_list_all_empty_when_blob_missing() -> None:
    assert plans.list_all() == []


def test_add_returns_item_and_persists() -> None:
    item = plans.add("Viaje a Cantabria", _future(5))
    assert item.label == "Viaje a Cantabria"
    assert item.id
    listed = plans.list_all()
    assert [it.id for it in listed] == [item.id]


def test_add_rejects_past_date() -> None:
    past = (date.today() - timedelta(days=1)).isoformat()
    with pytest.raises(ValueError, match="past"):
        plans.add("X", past)


def test_add_rejects_bad_iso_date() -> None:
    with pytest.raises(ValueError, match="bad date"):
        plans.add("X", "el viernes que viene")


def test_add_strips_label_and_rejects_empty() -> None:
    item = plans.add("  Boda  ", _future(10))
    assert item.label == "Boda"
    with pytest.raises(ValueError, match="empty"):
        plans.add("   ", _future(10))


def test_list_all_sorted_by_date_ascending() -> None:
    plans.add("Lejano", _future(30))
    plans.add("Pronto", _future(2))
    plans.add("Medio", _future(10))
    labels = [p.label for p in plans.list_all()]
    assert labels == ["Pronto", "Medio", "Lejano"]


def test_list_all_prunes_far_past(_fake_state: dict[str, Any]) -> None:
    # Old plan beyond the grace window — should be dropped on load.
    very_old = (date.today() - timedelta(days=90)).isoformat()
    near = _future(3)
    _fake_state["plans.json"] = [
        {"id": "old", "label": "Olvido", "date": very_old, "created_ms": 0},
        {"id": "new", "label": "Cantabria", "date": near, "created_ms": 0},
    ]
    listed = plans.list_all()
    assert [p.id for p in listed] == ["new"]


def test_remove_by_label_fuzzy() -> None:
    plans.add("Viaje a Cantabria", _future(5))
    plans.add("Boda de Marta", _future(15))
    removed = plans.remove("cantabria")
    assert removed.label == "Viaje a Cantabria"
    assert [p.label for p in plans.list_all()] == ["Boda de Marta"]


def test_remove_unknown_raises() -> None:
    plans.add("Cantabria", _future(5))
    with pytest.raises(plans.PlanNotFoundError):
        plans.remove("Tokio")


def test_days_until_zero_for_today() -> None:
    item = plans.add("Hoy", date.today().isoformat())
    assert plans.days_until(item) == 0


def test_to_wire_shape() -> None:
    plans.add("Viaje a Cantabria", _future(5))
    wire = plans.to_wire(plans.list_all())
    assert len(wire) == 1
    assert set(wire[0].keys()) == {"id", "label", "date", "days_until"}
    assert wire[0]["days_until"] == 5
