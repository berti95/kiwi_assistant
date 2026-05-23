#!/usr/bin/env python3
"""End-to-end smoke test of the Kiwi backend.

Records a few seconds of audio from the default mic, streams it to
/ws/session, prints transcripts and plays back the model's audio reply.
Useful for validating that Vertex AI Live is wired correctly without
needing the tablet.

Setup:
    pip install -r backend/scripts/requirements-test.txt

Usage (PowerShell):
    $env:CLOUD_RUN_URL = "https://kiwi-backend-XXXXX-ew.a.run.app"
    $env:KIWI_API_KEY  = "kwi_..."
    python backend/scripts/test_session.py

Usage (bash):
    CLOUD_RUN_URL=https://... KIWI_API_KEY=kwi_... \
        python backend/scripts/test_session.py [seconds]

The optional `seconds` argument controls the recording length (default 5).
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import sys

import numpy as np
import sounddevice as sd
import websockets

SAMPLE_RATE_IN = 16_000   # mic capture (Gemini Live input format)
SAMPLE_RATE_OUT = 24_000  # Gemini Live output format
CHANNELS = 1
DEFAULT_SECONDS = 5
CHUNK_MS = 50  # 50 ms chunks → ~1.6 KB per WS frame at 16 kHz


def ws_url_for(base_url: str) -> str:
    base = base_url.replace("https://", "wss://").replace("http://", "ws://")
    return base.rstrip("/") + "/ws/session"


def record(seconds: float) -> bytes:
    print(f"Recording {seconds:.1f}s — speak now...")
    audio = sd.rec(
        int(seconds * SAMPLE_RATE_IN),
        samplerate=SAMPLE_RATE_IN,
        channels=CHANNELS,
        dtype="int16",
    )
    sd.wait()
    print(f"  captured {audio.size * 2} bytes")
    return audio.tobytes()


async def stream_session(ws_url: str, api_key: str, audio_bytes: bytes) -> bytes:
    chunk_size = SAMPLE_RATE_IN * 2 * CHUNK_MS // 1000  # int16 → 2 bytes/sample
    response_audio = bytearray()

    async with websockets.connect(ws_url) as ws:
        await ws.send(json.dumps({"type": "session.start", "api_key": api_key}))

        ready = json.loads(await ws.recv())
        if ready.get("type") != "session.ready":
            raise RuntimeError(f"unexpected handshake reply: {ready}")
        print("Session ready")

        async def sender() -> None:
            for i in range(0, len(audio_bytes), chunk_size):
                chunk = audio_bytes[i : i + chunk_size]
                await ws.send(
                    json.dumps(
                        {
                            "type": "audio.input",
                            "data": base64.b64encode(chunk).decode("ascii"),
                        }
                    )
                )
                # Pace the upload so it roughly matches real-time playback
                # and Gemini's VAD has time to react.
                await asyncio.sleep(CHUNK_MS / 1000)
            await ws.send(json.dumps({"type": "audio.end"}))
            print("Audio sent, awaiting response...")

        async def receiver() -> None:
            async for raw in ws:
                msg = json.loads(raw)
                kind = msg.get("type")
                if kind == "audio.output":
                    response_audio.extend(base64.b64decode(msg["data"]))
                elif kind == "transcript.input":
                    print(f"  [you]  {msg.get('text', '')}")
                elif kind == "transcript.output":
                    print(f"  [kiwi] {msg.get('text', '')}")
                elif kind == "response.end":
                    return
                elif kind == "error":
                    raise RuntimeError(f"server error: {msg.get('message')}")
                else:
                    print(f"  ?? unknown frame: {msg}")

        await asyncio.gather(sender(), receiver())
        await ws.send(json.dumps({"type": "session.end"}))

    return bytes(response_audio)


def play(pcm_bytes: bytes) -> None:
    if not pcm_bytes:
        print("No audio in response.")
        return
    arr = np.frombuffer(pcm_bytes, dtype=np.int16)
    print(f"Playing {len(pcm_bytes)} bytes ({len(arr) / SAMPLE_RATE_OUT:.1f}s)...")
    sd.play(arr, samplerate=SAMPLE_RATE_OUT)
    sd.wait()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "seconds",
        nargs="?",
        type=float,
        default=DEFAULT_SECONDS,
        help=f"recording length in seconds (default {DEFAULT_SECONDS})",
    )
    args = parser.parse_args()

    base_url = os.environ.get("CLOUD_RUN_URL")
    api_key = os.environ.get("KIWI_API_KEY")
    if not base_url or not api_key:
        sys.exit("set CLOUD_RUN_URL and KIWI_API_KEY env vars")

    audio_bytes = record(args.seconds)
    ws_url = ws_url_for(base_url)
    print(f"Connecting to {ws_url}")
    response = asyncio.run(stream_session(ws_url, api_key, audio_bytes))
    play(response)


if __name__ == "__main__":
    main()
