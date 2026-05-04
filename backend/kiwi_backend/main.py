import logging
from datetime import UTC, datetime

from fastapi import FastAPI, Header, HTTPException, WebSocket
from pydantic import BaseModel, Field

from . import log_buffer
from .auth import is_valid_api_key
from .session import run_session
from .settings import settings

# Cloud Run's log capture relies on stdout/stderr, but Python's root
# logger defaults to WARNING which hides our per-turn INFO traces. Bump
# the kiwi_backend tree to INFO so multi-turn diagnostics show up in
# Cloud Logging without affecting noisy third-party libs.
logging.basicConfig(level=logging.INFO)
logging.getLogger("kiwi_backend").setLevel(logging.INFO)

# Mirror every kiwi_backend.* log record into an in-memory ring buffer so
# the developer can poll recent lines via GET /api/logs/recent without
# fetching from Cloud Logging.
log_buffer.install_handler()

# Dedicated logger for tablet-shipped lines so we can grep them out
# from the rest of the backend traffic when debugging.
tablet_log = logging.getLogger("kiwi_backend.tablet")

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


class _LogEntry(BaseModel):
    """One line of the tablet's app log, as shipped by ``LogShipper``."""

    ts_ms: int
    level: str = Field(min_length=1, max_length=4)
    tag: str = Field(min_length=1, max_length=64)
    message: str
    error: str | None = None


class _LogBatch(BaseModel):
    """A batch of log entries with the device's identity envelope."""

    device_id: str = Field(default="kiwi-tablet", max_length=64)
    version_code: int | None = None
    version_name: str | None = None
    entries: list[_LogEntry]


@app.post("/api/logs")
async def post_logs(
    batch: _LogBatch,
    x_api_key: str = Header(default=""),
) -> dict[str, int]:
    """Receive a batch of log lines from the tablet and dump them into
    Cloud Logging via stdout. Auth is the same shared API key the
    tablet uses for the WebSocket session.

    Cheap: a 200-line batch every 5 s is ~40 lines/s on the busy
    case, well below Cloud Logging's per-instance free tier.
    """
    if not is_valid_api_key(x_api_key):
        raise HTTPException(status_code=403, detail="invalid api key")

    header = ""
    if batch.version_name:
        header = f" v{batch.version_name}({batch.version_code})"

    for entry in batch.entries:
        ts = datetime.fromtimestamp(entry.ts_ms / 1000, tz=UTC).isoformat()
        line = (
            f"[{batch.device_id}{header} @ {ts}] "
            f"{entry.level}/{entry.tag}: {entry.message}"
        )
        if entry.error:
            line += f"\n{entry.error}"
        tablet_log.info(line)

    return {"received": len(batch.entries)}


@app.get("/api/logs/recent")
async def get_recent_logs(
    token: str = "",
    since: int = 0,
    limit: int = 500,
) -> dict[str, object]:
    """Return the latest log lines kept in memory.

    Auth: a separate ``DEV_LOGS_TOKEN`` env var (NOT the tablet's API
    key — different scope). Closed by default: an empty token in the
    settings means every request gets 403, so a forgotten production
    deploy doesn't accidentally expose the buffer.

    Pagination: pass back the ``next_since`` cursor from the previous
    response to receive only entries that are newer than what you've
    already seen. ``since=0`` returns the oldest still-buffered entry.
    """
    expected = settings.dev_logs_token
    if not expected or token != expected:
        raise HTTPException(status_code=403, detail="invalid dev token")
    capped_limit = max(1, min(limit, log_buffer.CAPACITY))
    entries, cursor = log_buffer.snapshot_since(since, capped_limit)
    return {
        "entries": entries,
        "count": len(entries),
        "next_since": cursor,
    }


@app.websocket("/ws/session")
async def ws_session(websocket: WebSocket) -> None:
    await run_session(websocket)
