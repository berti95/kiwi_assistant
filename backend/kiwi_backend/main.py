import logging

from fastapi import FastAPI, WebSocket

from .session import run_session
from .settings import settings

# Cloud Run's log capture relies on stdout/stderr, but Python's root
# logger defaults to WARNING which hides our per-turn INFO traces. Bump
# the kiwi_backend tree to INFO so multi-turn diagnostics show up in
# Cloud Logging without affecting noisy third-party libs.
logging.basicConfig(level=logging.INFO)
logging.getLogger("kiwi_backend").setLevel(logging.INFO)

app = FastAPI(title="Kiwi backend", version=settings.app_version_name)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/version")
async def version() -> dict[str, object]:
    return {
        "version_code": settings.app_version_code,
        "version_name": settings.app_version_name,
        "apk_url": settings.apk_url,
    }


@app.websocket("/ws/session")
async def ws_session(websocket: WebSocket) -> None:
    await run_session(websocket)
