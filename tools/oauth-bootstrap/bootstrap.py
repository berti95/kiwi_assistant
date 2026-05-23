"""One-shot OAuth bootstrap for Kiwi.

Runs the installed-app OAuth flow against Google to obtain a refresh
token with Calendar (read-only) + YouTube Data (read-only) scopes,
then stores the bundled credentials in GCP Secret Manager so the
Cloud Run backend can read them at startup and refresh access tokens
on demand.

Run this once per Google account that Kiwi should access. Re-running
adds a new secret version (`:latest` always wins).

Usage:
    cd tools/oauth-bootstrap
    pip install -r requirements.txt
    python bootstrap.py /path/to/client_secret.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys

from google.api_core import exceptions as gcp_exc
from google.cloud import secretmanager
from google_auth_oauthlib.flow import InstalledAppFlow

DEFAULT_PROJECT = "kiwi-assistant-494421"
DEFAULT_SECRET_NAME = "kiwi-google-credentials"
SCOPES = [
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/youtube.readonly",
]


def _ensure_secret(
    sm: secretmanager.SecretManagerServiceClient,
    parent: str,
    secret_id: str,
) -> str:
    """Create the secret if it doesn't exist. Returns the resource name."""
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
    parser = argparse.ArgumentParser(description="Kiwi Google OAuth bootstrap")
    parser.add_argument(
        "client_secret",
        help="Path to the client_secret.json downloaded from GCP Console.",
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

    if not os.path.isfile(args.client_secret):
        print(
            f"ERROR: client_secret not found at {args.client_secret}",
            file=sys.stderr,
        )
        return 2

    print("→ launching OAuth flow…")
    print("  scopes requested:")
    for s in SCOPES:
        print(f"    • {s}")
    print()
    print("  your browser will open shortly. If you see a 'Google hasn't")
    print("  verified this app' warning, click Advanced → Go to Kiwi (unsafe).")
    print()

    flow = InstalledAppFlow.from_client_secrets_file(args.client_secret, SCOPES)
    # access_type=offline + prompt=consent forces Google to ship a refresh
    # token even if you've authorised this client before.
    creds = flow.run_local_server(
        port=0,
        access_type="offline",
        prompt="consent",
    )
    if not creds.refresh_token:
        print(
            "ERROR: Google did not return a refresh token. Try again — make "
            "sure you completed the consent screen.",
            file=sys.stderr,
        )
        return 1

    # Bundle everything the backend needs to mint access tokens. Storing
    # the client_secret alongside the refresh_token is fine: both live in
    # the same Secret Manager entry and the refresh token alone wouldn't
    # be useful without the client_id/secret pair anyway.
    payload = {
        "refresh_token": creds.refresh_token,
        "client_id": creds.client_id,
        "client_secret": creds.client_secret,
        "token_uri": creds.token_uri,
        "scopes": list(creds.scopes or SCOPES),
    }

    sm = secretmanager.SecretManagerServiceClient()
    parent = f"projects/{args.project}"
    secret_path = _ensure_secret(sm, parent, args.secret_name)

    response = sm.add_secret_version(
        request={
            "parent": secret_path,
            "payload": {"data": json.dumps(payload).encode("utf-8")},
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
        "  3. The next backend deploy mounts the secret as the\n"
        "     KIWI_GOOGLE_CREDENTIALS env var (already configured in\n"
        "     .github/workflows/backend-deploy.yml).",
    )
    print(
        "  4. You can delete the local client_secret.json now — the bundle\n"
        "     lives in Secret Manager and the script only needed it once.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
