from kiwi_backend import auth
from kiwi_backend.settings import settings


def test_rejects_when_no_secret_configured(monkeypatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", "")
    assert auth.is_valid_api_key("anything") is False


def test_rejects_empty_candidate(monkeypatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", "kwi_secret")
    assert auth.is_valid_api_key("") is False
    assert auth.is_valid_api_key(None) is False


def test_accepts_matching_key(monkeypatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", "kwi_secret")
    assert auth.is_valid_api_key("kwi_secret") is True


def test_rejects_mismatching_key(monkeypatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", "kwi_secret")
    assert auth.is_valid_api_key("kwi_other") is False
