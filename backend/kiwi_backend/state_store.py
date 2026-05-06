"""Tiny GCS-backed JSON state store for things that need to outlive a request.

The backend is single-user and stateless on Cloud Run, so anything we
want to remember between turns (TODOs today, maybe small caches later)
needs an external store. A bucket holding one JSON blob per "table" is
the cheapest possible answer: pennies a year for our volumes, no API
to enable beyond regular Cloud Storage, and read/write are O(KB).

Each call reads/writes the whole blob. With <100 items per file that's
fine; if a state grows large enough that this matters we move it to
Firestore — not now.

Concurrent writers race the way you'd expect (last writer wins). We're
single-user with one Cloud Run replica handling a TODO at a time, so
this is an acceptable trade. Tests inject a fake module-level
``_load_blob`` / ``_save_blob`` so they don't touch GCS.
"""
from __future__ import annotations

import json
import logging
import unicodedata
from collections.abc import Callable
from functools import lru_cache
from typing import Any

from google.cloud import storage

from .settings import settings

log = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def _client() -> storage.Client:
    return storage.Client()


def _bucket_and_path(path: str) -> tuple[str, str]:
    """Resolve a logical path (e.g. ``"todos.json"``) to (bucket, blob)."""
    bucket = settings.kiwi_state_bucket
    if not bucket:
        raise RuntimeError(
            "KIWI_STATE_BUCKET is not configured. Create the bucket and set "
            "the env var to enable persistence.",
        )
    return bucket, path


def read_json(path: str, default: Any) -> Any:
    """Read a JSON blob from the state bucket. Returns ``default`` if missing.

    Any other failure (auth, network, malformed JSON) re-raises so the
    caller knows the state isn't usable. Defaulting silently on errors
    other than 404 would let TODO writes blow away an existing list
    because we'd think the file was empty.
    """
    bucket_name, blob_name = _bucket_and_path(path)
    blob = _client().bucket(bucket_name).blob(blob_name)
    if not blob.exists():
        return default
    raw = blob.download_as_bytes()
    if not raw:
        return default
    return json.loads(raw)


def write_json(path: str, payload: Any) -> None:
    """Overwrite a JSON blob in the state bucket."""
    bucket_name, blob_name = _bucket_and_path(path)
    blob = _client().bucket(bucket_name).blob(blob_name)
    blob.upload_from_string(
        json.dumps(payload, ensure_ascii=False),
        content_type="application/json; charset=utf-8",
    )


def fuzzy_norm(s: str) -> str:
    """Lowercase + strip diacritics. Cheap fuzzy-match key for short strings."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return s.lower().strip()


def fuzzy_match_one(
    items: list[Any], query: str, key: Callable[[Any], str],
) -> Any | None:
    """Fuzzy-find a single item. Exact > prefix > substring; None if ambiguous.

    Returns the matched item, or ``None`` when nothing reasonable
    matched. Multiple substring hits also return ``None`` so the caller
    can ask the user to disambiguate instead of guessing.
    """
    needle = fuzzy_norm(query)
    if not needle:
        return None
    candidates = [(it, fuzzy_norm(key(it))) for it in items]
    for it, k in candidates:
        if k == needle:
            return it
    prefix = [it for it, k in candidates if k.startswith(needle)]
    if len(prefix) == 1:
        return prefix[0]
    if len(prefix) > 1:
        return None
    substring = [it for it, k in candidates if needle in k]
    if len(substring) == 1:
        return substring[0]
    return None


def reset_cache() -> None:
    """Drop the cached storage Client — useful for tests."""
    _client.cache_clear()
