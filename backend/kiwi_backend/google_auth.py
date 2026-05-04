"""Lazy loader for the Google OAuth credentials shared by all tools.

The bootstrap script in ``tools/oauth-bootstrap/`` stashes a JSON
bundle in GCP Secret Manager. Cloud Run mounts it as the
``KIWI_GOOGLE_CREDENTIALS`` env var (via ``--set-secrets``); this
module hydrates that bundle into a
``google.oauth2.credentials.Credentials`` object that auto-refreshes
its access token on demand.

A single ``Credentials`` instance is cached per process. Google's
client libraries handle their own refresh locking and Cloud Run runs
the backend with concurrency=4 anyway, so there's no need for
per-request copies.

Tools that need Calendar / YouTube call ``credentials()`` and pass
the result into the matching API client. If the secret isn't mounted,
``credentials()`` raises ``GoogleAuthUnavailableError`` — the tool should
catch it and surface a clear error to Gemini instead of crashing the
proxy.
"""
from __future__ import annotations

import json
import logging
import os
from functools import lru_cache

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

log = logging.getLogger(__name__)

ENV_VAR = "KIWI_GOOGLE_CREDENTIALS"


class GoogleAuthUnavailableError(RuntimeError):
    """Raised when the Google credentials bundle is missing or unusable."""


@lru_cache(maxsize=1)
def credentials() -> Credentials:
    """Return shared Google credentials hydrated from the secret env var."""
    raw = os.environ.get(ENV_VAR)
    if not raw:
        raise GoogleAuthUnavailableError(
            f"{ENV_VAR} is empty — run tools/oauth-bootstrap/ first.",
        )
    try:
        bundle = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise GoogleAuthUnavailableError(
            f"{ENV_VAR} is not valid JSON: {exc}",
        ) from exc

    missing = [
        k for k in ("refresh_token", "client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        raise GoogleAuthUnavailableError(
            f"{ENV_VAR} is missing fields: {', '.join(missing)}. "
            "Re-run tools/oauth-bootstrap/.",
        )

    creds = Credentials(
        token=None,
        refresh_token=bundle["refresh_token"],
        client_id=bundle["client_id"],
        client_secret=bundle["client_secret"],
        token_uri=bundle["token_uri"],
        scopes=bundle.get("scopes"),
    )
    # Eager refresh: the first tool call doesn't pay the latency
    # penalty, and an obviously-broken secret (revoked refresh token,
    # wrong client id) blows up at startup rather than mid-conversation.
    try:
        creds.refresh(Request())
    except Exception as exc:
        log.exception("Google credentials refresh failed at startup")
        raise GoogleAuthUnavailableError(
            f"refresh failed: {type(exc).__name__}: {exc}. "
            "Re-run tools/oauth-bootstrap/ to mint a fresh refresh token.",
        ) from exc
    log.info(
        "Google credentials refreshed (scopes=%s, expiry=%s)",
        creds.scopes,
        creds.expiry,
    )
    return creds


def reset_cache() -> None:
    """Drop the cached ``Credentials`` — useful for tests."""
    credentials.cache_clear()
