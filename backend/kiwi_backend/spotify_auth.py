"""Lazy Spotify access-token holder shared by all Spotify tools.

The bootstrap script in ``tools/spotify-oauth-bootstrap/`` stashes a
JSON bundle (refresh_token + client_id + client_secret + token_uri
+ scopes) in GCP Secret Manager. Cloud Run mounts it as the
``KIWI_SPOTIFY_CREDENTIALS`` env var; this module hydrates it into a
reusable ``SpotifyToken`` that keeps the access token fresh.

A single token instance is cached per process. The same shape /
patterns as ``google_auth.py`` so anyone reading both can navigate
either with the same mental model.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from dataclasses import dataclass, field
from functools import lru_cache

import requests

log = logging.getLogger(__name__)

ENV_VAR = "KIWI_SPOTIFY_CREDENTIALS"
# Refresh anything that expires within REFRESH_LEEWAY_S so a tool
# call doesn't race a token expiry mid-request.
REFRESH_LEEWAY_S = 60


class SpotifyAuthUnavailableError(RuntimeError):
    """Raised when the Spotify credentials bundle is missing or unusable."""


@dataclass
class SpotifyToken:
    """Holds the Spotify auth state and refreshes the access token on demand.

    Construct via [token_holder]; don't instantiate directly.
    """

    refresh_token: str
    client_id: str
    client_secret: str
    token_uri: str
    scopes: list[str]
    _access_token: str = ""
    _expires_at: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def access_token(self) -> str:
        """Return a non-expired access token, refreshing if necessary."""
        with self._lock:
            if (
                self._access_token
                and time.time() < self._expires_at - REFRESH_LEEWAY_S
            ):
                return self._access_token
            log.info("refreshing Spotify access token")
            response = requests.post(
                self.token_uri,
                data={
                    "grant_type": "refresh_token",
                    "refresh_token": self.refresh_token,
                },
                auth=(self.client_id, self.client_secret),
                timeout=15,
            )
            if response.status_code != 200:
                raise SpotifyAuthUnavailableError(
                    f"refresh failed: HTTP {response.status_code} {response.text}"
                )
            payload = response.json()
            self._access_token = payload["access_token"]
            self._expires_at = time.time() + int(payload.get("expires_in", 3600))
            # Spotify *may* return a fresh refresh_token on rotation —
            # if it does, swap so the next refresh uses the new one.
            new_refresh = payload.get("refresh_token")
            if new_refresh and new_refresh != self.refresh_token:
                log.info("Spotify rotated the refresh token; updating in-memory.")
                self.refresh_token = new_refresh
            return self._access_token


@lru_cache(maxsize=1)
def token_holder() -> SpotifyToken:
    """Hydrate the credentials bundle from the env var into [SpotifyToken]."""
    raw = os.environ.get(ENV_VAR)
    if not raw:
        raise SpotifyAuthUnavailableError(
            f"{ENV_VAR} is empty — run tools/spotify-oauth-bootstrap/ first.",
        )
    try:
        bundle = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SpotifyAuthUnavailableError(
            f"{ENV_VAR} is not valid JSON: {exc}",
        ) from exc
    missing = [
        k for k in ("refresh_token", "client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        raise SpotifyAuthUnavailableError(
            f"{ENV_VAR} is missing fields: {', '.join(missing)}. "
            "Re-run tools/spotify-oauth-bootstrap/.",
        )
    holder = SpotifyToken(
        refresh_token=bundle["refresh_token"],
        client_id=bundle["client_id"],
        client_secret=bundle["client_secret"],
        token_uri=bundle["token_uri"],
        scopes=list(bundle.get("scopes") or []),
    )
    # Eager refresh so a broken bundle blows up at startup rather than
    # mid-conversation.
    holder.access_token()
    log.info(
        "Spotify token holder ready (scopes=%s, expires in %ds)",
        holder.scopes,
        int(holder._expires_at - time.time()),
    )
    return holder


def reset_cache() -> None:
    """Drop the cached holder — useful for tests."""
    token_holder.cache_clear()
