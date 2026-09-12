from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session

from app.hashing import verify_password
from app.models import Device, Owner
from app.util import pairing_token_entropy_ok
from tests.conftest import login, new_device_id, pairing_token, unpaired_hello


def test_password_is_hashed(app):
    SessionLocal = app.state.SessionLocal
    with SessionLocal() as db:
        owner = db.query(Owner).one()
        assert owner.password_hash
        assert "correct-horse-battery" not in owner.password_hash
        assert owner.password_hash.startswith("$argon2")
        assert verify_password("correct-horse-battery", owner.password_hash)


def test_pairing_and_session_tokens_are_hashed(client, app):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    assert pairing_token_entropy_ok(token)
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Home Phone")
        body = client.post(
            "/api/devices/pair",
            json={
                "v": 1,
                "server": "http://testserver",
                "device_id": device_id,
                "pairing_token": token,
                "name": "Home Phone",
            },
            headers={"X-CSRF-Token": csrf},
        ).json()
        assert body["ok"] is True
        session_token = ws.receive_json()["payload"]["session_token"]

    with app.state.SessionLocal() as db:
        device = db.query(Device).filter(Device.device_id == device_id).one()
        assert device.pairing_token_hash
        assert token not in device.pairing_token_hash
        assert session_token not in (device.device_session_hash or "")
        assert device.pairing_consumed is True


def test_state_changing_api_needs_csrf(client):
    login(client)
    response = client.post("/api/devices/pair", json={"v": 1})
    assert response.status_code == 403


def test_sqlite_has_no_plaintext_password(app, tmp_path):
    engine = create_engine(app.state.settings.DATABASE_URL)
    with engine.connect() as conn:
        rows = conn.execute(text("SELECT password_hash FROM owner")).fetchall()
    assert rows
    assert "correct-horse-battery" not in rows[0][0]
def test_liveness_and_readiness(client):
    assert client.get("/health").json()["ok"] is True
    ready = client.get("/ready")
    assert ready.status_code == 200
    assert ready.json() == {"ok": True, "ready": True, "storage": "sqlite"}
