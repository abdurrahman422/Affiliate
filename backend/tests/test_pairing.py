from tests.conftest import login, new_device_id, pairing_token, unpaired_hello


def _pair(client, csrf, device_id, token, name):
    return client.post(
        "/api/devices/pair",
        json={
            "v": 1,
            "server": "http://testserver",
            "device_id": device_id,
            "pairing_token": token,
            "name": name,
        },
        headers={"X-CSRF-Token": csrf},
    )


def test_pair_adds_device_and_issues_session(client):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        ack = unpaired_hello(ws, device_id, token, "Home Phone")
        assert ack["type"] == "UNPAIRED_ACK"
        response = _pair(client, csrf, device_id, token, "Home Phone")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["ok"] is True
        assert body["device"]["name"] == "Home Phone"
        paired = ws.receive_json()
        assert paired["type"] == "PAIR_SUCCESS"
        assert paired["payload"]["session_token"]
        assert paired["payload"]["session_token"] != token
        session_token = paired["payload"]["session_token"]

    listing = client.get("/api/devices")
    names = [d["name"] for d in listing.json()["devices"]]
    assert "Home Phone" in names

    with client.websocket_connect("/ws/device") as ws:
        ws.send_json(
            {
                "type": "DEVICE_AUTH",
                "device_id": device_id,
                "payload": {"session_token": session_token},
            }
        )
        auth = ws.receive_json()
        assert auth["type"] == "AUTH_OK"


def test_short_redeem_code_resolves_online_phone(client):
    csrf = login(client)
    device_id, code = new_device_id(), "Ab3dEf7hJk9mNp2rSt5wXy8z"
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, code, "Redeem Phone")
        response = client.post("/api/devices/pair", json={"redeem_code": code, "name": "Redeem Phone"}, headers={"X-CSRF-Token": csrf})
        assert response.status_code == 200, response.text
        assert ws.receive_json()["type"] == "PAIR_SUCCESS"


def test_pairing_token_cannot_be_reused(client):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Home Phone")
        first = _pair(client, csrf, device_id, token, "Home Phone")
        assert first.status_code == 200
        ws.receive_json()
        second = _pair(client, csrf, device_id, token, "Home Phone")
        assert second.status_code == 400
        assert second.json()["code"] == "TOKEN_CONSUMED"


def test_remove_revokes_session(client):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Home Phone")
        assert _pair(client, csrf, device_id, token, "Home Phone").status_code == 200
        session_token = ws.receive_json()["payload"]["session_token"]

    removed = client.post(
        f"/api/devices/{device_id}/remove",
        json={},
        headers={"X-CSRF-Token": csrf},
    )
    assert removed.status_code == 200
    listing = client.get("/api/devices").json()["devices"]
    assert listing == []

    with client.websocket_connect("/ws/device") as ws:
        ws.send_json(
            {
                "type": "DEVICE_AUTH",
                "device_id": device_id,
                "payload": {"session_token": session_token},
            }
        )
        err = ws.receive_json()
        assert err["type"] == "ERROR"
        assert err["payload"]["code"] in {"NOT_PAIRED", "AUTH_FAILED"}


def test_removed_device_can_pair_with_new_token(client):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Home Phone")
        assert _pair(client, csrf, device_id, token, "Home Phone").status_code == 200
        ws.receive_json()

    assert client.post(
        f"/api/devices/{device_id}/remove",
        json={},
        headers={"X-CSRF-Token": csrf},
    ).status_code == 200

    new_token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        ack = unpaired_hello(ws, device_id, new_token, "Home Phone")
        assert ack["type"] == "UNPAIRED_ACK"
        reused = _pair(client, csrf, device_id, token, "Home Phone")
        assert reused.status_code == 400
        fresh = _pair(client, csrf, device_id, new_token, "Home Phone")
        assert fresh.status_code == 200
        assert ws.receive_json()["type"] == "PAIR_SUCCESS"


def test_device_can_disconnect_its_own_dashboard_session(client):
    csrf = login(client)
    device_id = new_device_id(); token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Home Phone")
        assert _pair(client, csrf, device_id, token, "Home Phone").status_code == 200
        session = ws.receive_json()["payload"]["session_token"]
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"v": 1, "type": "DEVICE_AUTH", "device_id": device_id, "payload": {"session_token": session}})
        assert ws.receive_json()["type"] == "AUTH_OK"
        ws.send_json({"v": 1, "type": "DASHBOARD_DISCONNECT", "device_id": device_id, "payload": {}})
        assert ws.receive_json()["type"] == "REVOKED"
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"v": 1, "type": "DEVICE_AUTH", "device_id": device_id, "payload": {"session_token": session}})
        assert ws.receive_json()["payload"]["code"] in {"NOT_PAIRED", "AUTH_FAILED"}
