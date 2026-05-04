"""In-memory ring buffer of recent log entries for ad-hoc debugging.

Every record emitted on the ``kiwi_backend`` logger tree (backend
itself + tablet-shipped lines that get re-logged via
``kiwi_backend.tablet``) is appended here, with a monotonic
sequence number. The ``GET /api/logs/recent`` endpoint reads from
this buffer so the developer can poll the latest events without
fetching from Cloud Logging.

Bounded ([CAPACITY]) so a forgotten Cloud Run instance can't grow
unbounded. Survives across requests inside the same Cloud Run
container; cold starts wipe it (acceptable trade-off for zero infra).
"""
from __future__ import annotations

import logging
import threading
from collections import deque
from dataclasses import dataclass

CAPACITY = 5_000


@dataclass(frozen=True)
class _BufferedEntry:
    seq: int
    ts_ms: int
    level: str
    logger: str
    message: str


class _LogRingBuffer:
    """Thread-safe ring buffer of recent log entries."""

    def __init__(self, capacity: int = CAPACITY) -> None:
        self._buf: deque[_BufferedEntry] = deque(maxlen=capacity)
        self._next_seq = 1
        self._lock = threading.Lock()

    def append(self, ts_ms: int, level: str, logger: str, message: str) -> None:
        with self._lock:
            entry = _BufferedEntry(
                seq=self._next_seq,
                ts_ms=ts_ms,
                level=level,
                logger=logger,
                message=message,
            )
            self._next_seq += 1
            self._buf.append(entry)

    def snapshot_since(
        self, since_seq: int, limit: int,
    ) -> tuple[list[dict], int]:
        """Return entries with seq > since_seq (up to limit) and the cursor.

        The cursor is the seq of the last entry returned (or the current
        ``next_seq - 1`` when nothing matched), so the caller can pass
        it back as ``since_seq`` next time and pick up where it left off.
        """
        with self._lock:
            high_water = self._next_seq - 1
            matching: list[_BufferedEntry] = []
            for entry in self._buf:
                if entry.seq > since_seq:
                    matching.append(entry)
                    if len(matching) >= limit:
                        break
            cursor = matching[-1].seq if matching else high_water
        return (
            [
                {
                    "seq": e.seq,
                    "ts_ms": e.ts_ms,
                    "level": e.level,
                    "logger": e.logger,
                    "message": e.message,
                }
                for e in matching
            ],
            cursor,
        )

    def reset(self) -> None:
        """Test helper — wipe state between cases."""
        with self._lock:
            self._buf.clear()
            self._next_seq = 1


_buffer = _LogRingBuffer()


def append_record(record: logging.LogRecord) -> None:
    """Append a stdlib ``LogRecord`` to the buffer."""
    _buffer.append(
        ts_ms=int(record.created * 1000),
        level=record.levelname,
        logger=record.name,
        message=record.getMessage(),
    )


def snapshot_since(since_seq: int, limit: int) -> tuple[list[dict], int]:
    return _buffer.snapshot_since(since_seq, limit)


def reset() -> None:
    """Test helper."""
    _buffer.reset()


class _BufferingHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            append_record(record)
        except Exception:
            # The handler must never raise — would break any logger.warn
            # call site downstream. Worst case we drop a buffered line.
            self.handleError(record)


def install_handler(logger_name: str = "kiwi_backend") -> None:
    """Attach the buffering handler to the named logger sub-tree.

    Idempotent: re-importing the module won't double-attach because the
    module-level handler instance is unique. We still guard against an
    explicit double install_handler call from tests, etc.
    """
    target = logging.getLogger(logger_name)
    for existing in target.handlers:
        if isinstance(existing, _BufferingHandler):
            return
    handler = _BufferingHandler(level=logging.DEBUG)
    target.addHandler(handler)
