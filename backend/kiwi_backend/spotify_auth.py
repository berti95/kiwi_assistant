"""Lazy Spotify access-token holder shared by all Spotify tools.

The bootstrap script in ``tools/spotify-oauth-bootstrap/`` stashes a
JSON bundle (refresh_token + client_id + client_secret + token_uri
+ scopes) in GCP Secret Manager. Cloud Run mounts it as the
``KIWI_SPOTIFY_CREDENTIALS`` env var; this module hydrates it into a
reusable ``SpotifyToken`` that keeps the access token fresh.

Runtime refresh
---------------

Refresh tokens get revoked / expired periodically (Spotify does this
for inactive apps, after password changes, etc.). When that happens
the env var alone is not enough — we need the user to re-OAuth and
the new refresh token to land somewhere the running backend can read
without a redeploy.

Same shape as ``google_auth``: GCS override on top of the env var. The
web OAuth flow in ``main.py`` calls ``save_bundle()`` after a
successful exchange; the next ``token_holder()`` picks the new bundle
up from GCS.

For the tablet to surface a "Renovar Spotify" affordance even when the
user isn't actively in NowPlaying, ``auth_required()`` exposes the
last refresh failure so ``/api/home`` can include a flag.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Any

import requests

from . import state_store

log = logging.getLogger(__name__)

ENV_VAR = "KIWI_SPOTIFY_CREDENTIALS"
GCS_OVERRIDE_PATH = "spotify-creds.json"
# Refresh anything that expires within REFRESH_LEEWAY_S so a tool
# call doesn't race a token expiry mid-request.
REFRESH_LEEWAY_S = 60


class SpotifyAuthUnavailableError(RuntimeError):
    """Raised when the Spotify credentials bundle is missing or unusable."""


# Module-level state — cached holder + last error message. Behind a
# lock so concurrent requests don't race against save_bundle().
_LOCK = threading.Lock()
_cached_holder: SpotifyToken | None = None
_cached_bundle: dict[str, Any] | None = None
_last_error: str | None = None


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
        global _last_error
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
                msg = (
                    f"refresh failed: HTTP {response.status_code} {response.text}. "
                    "Toca 'Renovar Spotify' en el tablet."
                )
                _last_error = msg
                raise SpotifyAuthUnavailableError(msg)
            # Clear last_error from a previous failed refresh: the
            # bundle works again. Useful after a successful Renovar
            # — the home flag goes away on next /api/home.
            _last_error = None
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


def token_holder() -> SpotifyToken:
    """Return the shared token holder, hydrating from bundle on first use."""
    global _cached_holder
    with _LOCK:
        if _cached_holder is not None:
            return _cached_holder
        _cached_holder = _build_holder()
        return _cached_holder


def _build_holder() -> SpotifyToken:
    """Load the bundle and eagerly refresh so broken setups fail at startup."""
    global _last_error
    bundle = _load_bundle()
    missing = [
        k for k in ("refresh_token", "client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        msg = (
            f"{ENV_VAR} is missing fields: {', '.join(missing)}. "
            "Toca 'Renovar Spotify' en el tablet o vuelve a correr "
            "tools/spotify-oauth-bootstrap/."
        )
        _last_error = msg
        raise SpotifyAuthUnavailableError(msg)
    holder = SpotifyToken(
        refresh_token=bundle["refresh_token"],
        client_id=bundle["client_id"],
        client_secret=bundle["client_secret"],
        token_uri=bundle["token_uri"],
        scopes=list(bundle.get("scopes") or []),
    )
    # Eager refresh so a broken bundle blows up at startup rather than
    # mid-conversation. Also clears _last_error on success (via
    # access_token).
    holder.access_token()
    log.info(
        "Spotify token holder ready (scopes=%s, expires in %ds)",
        holder.scopes,
        int(holder._expires_at - time.time()),
    )
    return holder


def _load_bundle() -> dict[str, Any]:
    """Resolve the credentials bundle. GCS override wins over env var."""
    global _cached_bundle
    if _cached_bundle is not None:
        return _cached_bundle

    # GCS override first — where save_bundle() lands after a
    # successful in-app OAuth.
    try:
        override = state_store.read_json(GCS_OVERRIDE_PATH, default=None)
    except Exception as exc:  # noqa: BLE001 — GCS errors degrade to env var
        log.info("GCS override unavailable, falling back to env var: %s", exc)
        override = None
    if isinstance(override, dict) and override.get("refresh_token"):
        _cached_bundle = override
        log.info("Loaded Spotify credentials bundle from GCS override")
        return override

    raw = os.environ.get(ENV_VAR)
    if not raw:
        msg = (
            f"{ENV_VAR} is empty and no GCS override present — "
            "run tools/spotify-oauth-bootstrap/ first."
        )
        global _last_error
        _last_error = msg
        raise SpotifyAuthUnavailableError(msg)
    try:
        bundle = json.loads(raw)
    except json.JSONDecodeError as exc:
        msg = f"{ENV_VAR} is not valid JSON: {exc}"
        _last_error = msg
        raise SpotifyAuthUnavailableError(msg) from exc
    _cached_bundle = bundle
    log.info("Loaded Spotify credentials bundle from env var")
    return bundle


def save_bundle(bundle: dict[str, Any]) -> None:
    """Persist a new bundle (post-OAuth) and drop the cached holder.

    Writes to GCS so the next request — and a future container
    restart — picks it up without redeploying.
    """
    global _cached_holder, _cached_bundle, _last_error
    required = {"refresh_token", "client_id", "client_secret", "token_uri"}
    missing = required - bundle.keys()
    if missing:
        raise ValueError(f"bundle missing fields: {sorted(missing)}")
    with _LOCK:
        state_store.write_json(GCS_OVERRIDE_PATH, bundle)
        _cached_bundle = dict(bundle)
        _cached_holder = None  # next token_holder() rebuilds + refreshes
        _last_error = None
    log.info("Saved new Spotify credentials bundle (scopes=%s)", bundle.get("scopes"))


def client_config() -> dict[str, Any]:
    """Return the OAuth client config (no refresh_token).

    Used by ``/oauth/spotify/start`` to build the consent URL. Reads
    from the cached bundle if available, otherwise loads fresh.
    """
    with _LOCK:
        bundle = _cached_bundle if _cached_bundle is not None else _load_bundle()
    missing = [
        k for k in ("client_id", "client_secret", "token_uri")
        if not bundle.get(k)
    ]
    if missing:
        raise SpotifyAuthUnavailableError(
            f"client config is missing: {', '.join(missing)}. The initial "
            "env var bundle is required even when re-OAuthing — re-run "
            "tools/spotify-oauth-bootstrap/.",
        )
    return {
        "client_id": bundle["client_id"],
        "client_secret": bundle["client_secret"],
        "token_uri": bundle["token_uri"],
        "scopes": list(bundle.get("scopes") or []),
    }


def auth_required() -> bool:
    """True when the holder is in a known-broken state.

    Read by ``/api/home`` so the tablet can light up the "Renovar
    Spotify" chip without waiting for the user to enter NowPlaying.
    Cleared automatically on the next successful refresh or
    ``save_bundle``.
    """
    return _last_error is not None


def last_error_message() -> str | None:
    """Last refresh error, for logs / debugging. ``None`` when healthy."""
    return _last_error


def reset_cache() -> None:
    """Drop the cached holder + bundle + error. Used by tests."""
    global _cached_holder, _cached_bundle, _last_error
    with _LOCK:
        _cached_holder = None
        _cached_bundle = None
        _last_error = None
