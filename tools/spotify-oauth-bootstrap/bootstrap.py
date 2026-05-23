"""One-shot Spotify OAuth bootstrap for Kiwi.

Runs the Authorization Code flow against Spotify to obtain a refresh
token, then stores the bundled credentials in GCP Secret Manager so
the Cloud Run backend can read them at startup and refresh access
tokens on demand.

Run this once. Re-running adds a new secret version (``:latest``
always wins).

Usage:
    cd tools/spotify-oauth-bootstrap
    pip install -r requirements.txt
    export SPOTIFY_CLIENT_ID=7c9b44d1...   # public, fine on the env
    # Either:
    #   read -s SPOTIFY_CLIENT_SECRET    (linux/mac, hides the input)
    #   $env:SPOTIFY_CLIENT_SECRET = "..." (PowerShell)
    python bootstrap.py
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
import secrets as _secrets
import sys
import threading
import urllib.parse
import webbrowser
from typing import Optional

import requests
from google.api_core import exceptions as gcp_exc
from google.cloud import secretmanager

DEFAULT_PROJECT = "kiwi-assistant-494421"
DEFAULT_SECRET_NAME = "kiwi-spotify-credentials"
REDIRECT_URI = "http://127.0.0.1:8765/callback"
AUTH_URL = "https://accounts.spotify.com/authorize"
TOKEN_URL = "https://accounts.spotify.com/api/token"
SCOPES = [
    # Playback control + state.
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-read-currently-playing",
    "user-read-recently-played",
    # Library + content.
    "user-library-read",
    "playlist-read-private",
    # Required by the Web Playback SDK so a future fase can register
    # the tablet itself as a Spotify Connect device. Asking for it
    # now means the user only goes through OAuth consent once.
    "streaming",
]


class _CodeReceiver:
    """Holds the result of the OAuth callback."""

    def __init__(self) -> None:
        self.code: Optional[str] = None
        self.error: Optional[str] = None
        self.state_mismatch: bool = False
        self.event = threading.Event()


def _build_handler(receiver: _CodeReceiver, expected_state: str):
    class _Handler(http.server.BaseHTTPRequestHandler):
        # Silence the noisy default access log so the terminal stays
        # readable while the user is in the browser.
        def log_message(self, *_args, **_kwargs) -> None:
            return

        def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler)
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            params = urllib.parse.parse_qs(parsed.query)
            if "error" in params:
                receiver.error = params["error"][0]
            elif "code" in params:
                state = params.get("state", [""])[0]
                if state != expected_state:
                    receiver.state_mismatch = True
                else:
                    receiver.code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            body = (
                "<html><body style='font-family:sans-serif;text-align:center;"
                "padding:64px;'>"
                "<h2>✓ Listo</h2>"
                "<p>Vuelve a la terminal — Kiwi ya tiene tu refresh token.</p>"
                "</body></html>"
            )
            self.wfile.write(body.encode("utf-8"))
            receiver.event.set()

    return _Handler


def _run_local_server(receiver: _CodeReceiver, expected_state: str) -> None:
    """Block until the OAuth callback fires (or the user aborts)."""
    handler = _build_handler(receiver, expected_state)
    server = http.server.HTTPServer(("127.0.0.1", 8765), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        receiver.event.wait()
    finally:
        server.shutdown()
        server.server_close()


def _ensure_secret(
    sm: secretmanager.SecretManagerServiceClient,
    parent: str,
    secret_id: str,
) -> str:
    secret_path = f"{parent}/secrets/{secret_id}"
    try:
        sm.get_secret(name=secret_path)
        print(f"→ secret '{secret_id}' already exists, will add a new version.")
    except gcp_exc.NotFound:
        print(f"→ creating secret '{secret_id}'…")
        sm.create_secret(
            request={
                "parent": parent,
                "secret_id": secret_id,
                "secret": {"replication": {"automatic": {}}},
            },
        )
    return secret_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Kiwi Spotify OAuth bootstrap")
    parser.add_argument(
        "--client-id",
        default=os.environ.get("SPOTIFY_CLIENT_ID", ""),
        help="Spotify app Client ID (or env SPOTIFY_CLIENT_ID).",
    )
    parser.add_argument(
        "--client-secret",
        default=os.environ.get("SPOTIFY_CLIENT_SECRET", ""),
        help="Spotify app Client Secret (or env SPOTIFY_CLIENT_SECRET).",
    )
    parser.add_argument(
        "--project",
        default=os.environ.get("GCP_PROJECT", DEFAULT_PROJECT),
        help=f"GCP project ID (default: {DEFAULT_PROJECT}).",
    )
    parser.add_argument(
        "--secret-name",
        default=DEFAULT_SECRET_NAME,
        help=f"Secret Manager secret ID (default: {DEFAULT_SECRET_NAME}).",
    )
    args = parser.parse_args()

    if not args.client_id or not args.client_secret:
        print(
            "ERROR: client_id and client_secret are required. Pass them via "
            "--client-id / --client-secret or env vars SPOTIFY_CLIENT_ID / "
            "SPOTIFY_CLIENT_SECRET.",
            file=sys.stderr,
        )
        return 2

    state = _secrets.token_urlsafe(16)
    auth_params = {
        "client_id": args.client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": " ".join(SCOPES),
        "state": state,
        # Always show the consent screen so a re-bootstrap reliably
        # produces a fresh refresh token even if the user already
        # authorised this app for these scopes.
        "show_dialog": "true",
    }
    auth_url = f"{AUTH_URL}?{urllib.parse.urlencode(auth_params)}"

    print("→ opening Spotify consent in your browser…")
    print("  scopes requested:")
    for s in SCOPES:
        print(f"    • {s}")
    print()
    print(f"  if the browser doesn't open, paste this URL manually:\n  {auth_url}")
    print()
    webbrowser.open(auth_url)

    receiver = _CodeReceiver()
    _run_local_server(receiver, state)

    if receiver.error:
        print(f"ERROR: Spotify returned error: {receiver.error}", file=sys.stderr)
        return 1
    if receiver.state_mismatch:
        print(
            "ERROR: state mismatch on the callback. "
            "Possible CSRF or stale browser tab — try again.",
            file=sys.stderr,
        )
        return 1
    if not receiver.code:
        print("ERROR: no authorization code received.", file=sys.stderr)
        return 1

    print("→ exchanging authorization code for tokens…")
    response = requests.post(
        TOKEN_URL,
        data={
            "grant_type": "authorization_code",
            "code": receiver.code,
            "redirect_uri": REDIRECT_URI,
        },
        auth=(args.client_id, args.client_secret),
        timeout=15,
    )
    if response.status_code != 200:
        print(
            f"ERROR: token exchange failed ({response.status_code}):\n"
            f"{response.text}",
            file=sys.stderr,
        )
        return 1
    payload = response.json()
    refresh_token = payload.get("refresh_token")
    if not refresh_token:
        print(
            "ERROR: Spotify did not return a refresh_token. "
            "Re-run the script.",
            file=sys.stderr,
        )
        return 1

    bundle = {
        "refresh_token": refresh_token,
        "client_id": args.client_id,
        "client_secret": args.client_secret,
        "token_uri": TOKEN_URL,
        "scopes": SCOPES,
    }

    sm = secretmanager.SecretManagerServiceClient()
    parent = f"projects/{args.project}"
    secret_path = _ensure_secret(sm, parent, args.secret_name)
    response = sm.add_secret_version(
        request={
            "parent": secret_path,
            "payload": {"data": json.dumps(bundle).encode("utf-8")},
        },
    )
    print()
    print(f"✓ refresh token stored at {response.name}")
    print()
    print("Next steps:")
    print(
        "  1. Verify in console:\n"
        f"     https://console.cloud.google.com/security/secret-manager/"
        f"secret/{args.secret_name}/versions?project={args.project}",
    )
    print(
        "  2. Grant the Cloud Run service account read access (one-off):\n"
        f"     gcloud secrets add-iam-policy-binding {args.secret_name} \\\n"
        "       --member=serviceAccount:kiwi-backend@"
        f"{args.project}.iam.gserviceaccount.com \\\n"
        "       --role=roles/secretmanager.secretAccessor",
    )
    print(
        "  3. Tell Claude to merge the workflow change that mounts\n"
        "     KIWI_SPOTIFY_CREDENTIALS=kiwi-spotify-credentials:latest.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
