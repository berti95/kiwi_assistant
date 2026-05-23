"""Lazy loader for the Google OAuth credentials shared by all tools.

The bootstrap script in ``tools/oauth-bootstrap/`` mints an initial
bundle and stashes it in GCP Secret Manager. Cloud Run mounts it as
the ``KIWI_GOOGLE_CREDENTIALS`` env var (via ``--set-secrets``). At
runtime, this module hydrates the bundle into a
``google.oauth2.credentials.Credentials`` object that auto-refreshes
its access token on demand.

Runtime refresh
---------------

Refresh tokens get revoked / expired periodically (Google does this
for inactive apps, after password changes, etc.). When that happens
the env var alone is not enough — we need the user to re-OAuth and
the new refresh token to land somewhere the running backend can read
without a redeploy.

We use the existing state bucket (``kiwi-assistant-494421-state``)
as that "runtime override" layer: ``google-creds.json`` overrides the
env var when present. The web OAuth flow in ``main.py`` calls
``save_bundle()`` after a successful exchange; the next call to
``credentials()`` picks up the new bundle from GCS.

Tools that need Calendar / YouTube call ``credentials()`` and pass
the result into the matching API client. If the bundle isn't usable,
``credentials()`` raises ``GoogleAuthUnavailableError`` — the tool
should catch it and surface a clear error to Gemini and the tablet
instead of crashing the proxy.
"""
from __future__ import annotations

import json
import logging
import os
import threading
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

from . import state_store

log = logging.getLogger(__name__)

ENV_VAR = "KIWI_GOOGLE_CREDENTIALS"
GCS_OVERRIDE_PATH = "google-creds.json"


class GoogleAuthUnavailableError(RuntimeError):
    """Raised when the Google credentials bundle is missing or unusable."""


# Cached Credentials + bundle source-of-truth. Mutations behind a
# lock so concurrent requests don't race during a save_bundle call.
_LOCK = threading.Lock()
_cached_creds: Credentials | None = None
_cached_bundle: dict[str, Any] | None = None
_last_error: str | None = None


def credentials() -> Credentials:
    """Return shared Google credentials, refreshing on demand."""
    global _cached_creds, _last_error
    with _LOCK:
        if _cached_creds is None:
            _cached_creds = _load_credentials()
            _last_error = None
        return _cached_creds


def _load_credentials() -> Credentials:
    """Build and eagerly refresh a Credentials from the current bundle."""
    global _last_error
    bundle = _load_bundle()
    missing = [
        k for k in ("refresh_token", "client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        msg = (
            f"credentials bundle is missing fields: {', '.join(missing)}. "
            "Re-run tools/oauth-bootstrap/ or use the in-app 'Renovar "
            "Google' flow."
        )
        _last_error = msg
        raise GoogleAuthUnavailableError(msg)

    creds = Credentials(
        token=None,
        refresh_token=bundle["refresh_token"],
        client_id=bundle["client_id"],
        client_secret=bundle["client_secret"],
        token_uri=bundle["token_uri"],
        scopes=bundle.get("scopes"),
    )
    # Eager refresh: the first tool call doesn't pay the latency
    # penalty, and an obviously-broken refresh token blows up at
    # startup rather than mid-conversation.
    try:
        creds.refresh(Request())
    except Exception as exc:
        log.exception("Google credentials refresh failed")
        msg = (
            f"refresh failed: {type(exc).__name__}: {exc}. "
            "Toca 'Renovar Google' en el tablet o vuelve a correr "
            "tools/oauth-bootstrap/."
        )
        _last_error = msg
        raise GoogleAuthUnavailableError(msg) from exc
    log.info(
        "Google credentials refreshed (scopes=%s, expiry=%s)",
        creds.scopes,
        creds.expiry,
    )
    return creds


def _load_bundle() -> dict[str, Any]:
    """Resolve the credentials bundle. GCS override wins over env var."""
    global _cached_bundle
    if _cached_bundle is not None:
        return _cached_bundle

    # Try GCS override first — that's where save_bundle writes after a
    # successful in-app OAuth.
    try:
        override = state_store.read_json(GCS_OVERRIDE_PATH, default=None)
    except Exception as exc:  # noqa: BLE001 — GCS errors degrade to env var
        log.info("GCS override unavailable, falling back to env var: %s", exc)
        override = None
    if isinstance(override, dict) and override.get("refresh_token"):
        _cached_bundle = override
        log.info("Loaded Google credentials bundle from GCS override")
        return override

    # Fallback: the initial bundle from Secret Manager via env var.
    raw = os.environ.get(ENV_VAR)
    if not raw:
        raise GoogleAuthUnavailableError(
            f"{ENV_VAR} is empty and no GCS override present — "
            "run tools/oauth-bootstrap/ first.",
        )
    try:
        bundle = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise GoogleAuthUnavailableError(
            f"{ENV_VAR} is not valid JSON: {exc}",
        ) from exc
    _cached_bundle = bundle
    log.info("Loaded Google credentials bundle from env var")
    return bundle


def save_bundle(bundle: dict[str, Any]) -> None:
    """Persist a new bundle and reset the cache.

    Called from the OAuth web-flow callback after exchanging the auth
    code for a fresh refresh token. Writes to GCS so the next request
    (and a future container restart) picks it up.
    """
    global _cached_creds, _cached_bundle, _last_error
    required = {"refresh_token", "client_id", "client_secret", "token_uri"}
    missing = required - bundle.keys()
    if missing:
        raise ValueError(f"bundle missing fields: {sorted(missing)}")
    with _LOCK:
        state_store.write_json(GCS_OVERRIDE_PATH, bundle)
        _cached_bundle = dict(bundle)
        _cached_creds = None  # next credentials() reloads + refreshes
        _last_error = None
    log.info("Saved new Google credentials bundle (scopes=%s)", bundle.get("scopes"))


def client_config() -> dict[str, Any]:
    """Return the OAuth client config (no refresh_token).

    Used by the /oauth/google/start endpoint to build the consent
    URL. Reads from the cached bundle if available, otherwise loads
    fresh (which surfaces a clear error if the bundle is completely
    missing — at minimum we need client_id/secret to bootstrap).
    """
    with _LOCK:
        bundle = _cached_bundle if _cached_bundle is not None else _load_bundle()
    missing = [
        k for k in ("client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        raise GoogleAuthUnavailableError(
            f"client config is missing: {', '.join(missing)}. The initial "
            "env var bundle is required even when re-OAuthing — re-run "
            "tools/oauth-bootstrap/.",
        )
    return {
        "client_id": bundle["client_id"],
        "client_secret": bundle["client_secret"],
        "token_uri": bundle["token_uri"],
        "scopes": bundle.get("scopes") or [],
    }


def last_error() -> str | None:
    """Last error from a failed credentials() call (None on success)."""
    return _last_error


def reset_cache() -> None:
    """Drop the cached Credentials + bundle. Used by tests."""
    global _cached_creds, _cached_bundle, _last_error
    with _LOCK:
        _cached_creds = None
        _cached_bundle = None
        _last_error = None
