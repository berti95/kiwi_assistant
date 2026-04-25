from fastapi.testclient import TestClient

from kiwi_backend.main import app

client = TestClient(app)


def test_health_returns_ok() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_version_payload_shape() -> None:
    response = client.get("/api/version")
    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) == {"version_code", "version_name", "apk_url"}
    assert isinstance(body["version_code"], int)
    assert isinstance(body["version_name"], str)
    assert isinstance(body["apk_url"], str)
