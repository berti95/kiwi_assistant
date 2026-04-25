import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from kiwi_backend import protocol
from kiwi_backend.main import app
from kiwi_backend.settings import settings

API_KEY = "kwi_test_secret"


@pytest.fixture(autouse=True)
def _configure_auth(monkeypatch) -> None:
    monkeypatch.setattr(settings, "kiwi_api_key", API_KEY)


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_first_message_must_be_session_start(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.send_json({"type": protocol.TYPE_AUDIO_INPUT, "data": "AAAA"})
        with pytest.raises(WebSocketDisconnect) as excinfo:
            ws.receive_json()
        assert excinfo.value.code == protocol.CLOSE_EXPECTED_SESSION_START


def test_invalid_api_key_is_rejected(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.send_json({"type": protocol.TYPE_SESSION_START, "api_key": "wrong"})
        with pytest.raises(WebSocketDisconnect) as excinfo:
            ws.receive_json()
        assert excinfo.value.code == protocol.CLOSE_INVALID_API_KEY


def test_handshake_then_audio_echo(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.send_json({"type": protocol.TYPE_SESSION_START, "api_key": API_KEY})
        assert ws.receive_json() == {"type": protocol.TYPE_SESSION_READY}

        ws.send_json({"type": protocol.TYPE_AUDIO_INPUT, "data": "QUJD"})
        assert ws.receive_json() == {
            "type": protocol.TYPE_AUDIO_OUTPUT,
            "data": "QUJD",
        }

        ws.send_json({"type": protocol.TYPE_AUDIO_END})
        assert ws.receive_json()["type"] == protocol.TYPE_TRANSCRIPT_INPUT
        assert ws.receive_json()["type"] == protocol.TYPE_TRANSCRIPT_OUTPUT
        assert ws.receive_json() == {"type": protocol.TYPE_RESPONSE_END}

        ws.send_json({"type": protocol.TYPE_SESSION_END})


def test_unknown_message_type_returns_error(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.send_json({"type": protocol.TYPE_SESSION_START, "api_key": API_KEY})
        ws.receive_json()  # session.ready

        ws.send_json({"type": "bogus"})
        msg = ws.receive_json()
        assert msg["type"] == protocol.TYPE_ERROR
