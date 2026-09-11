from __future__ import annotations

from conftest import new_device_id, pairing_token, unpaired_hello


def hello(ws, contact_id: str, public_key: str = "A" * 64):
    ws.send_json({"type": "CHAT_IDENTITY_HELLO", "device_id": "ignored", "payload": {
        "contact_id": contact_id, "public_key": public_key, "name": contact_id,
    }})
    return ws.receive_json()


def test_exact_contact_routing_binds_sender_to_socket(client):
    a, b = new_device_id(), new_device_id()
    with client.websocket_connect("/ws/device") as wa, client.websocket_connect("/ws/device") as wb:
        unpaired_hello(wa, a, pairing_token(), "A")
        unpaired_hello(wb, b, pairing_token(), "B")
        assert hello(wa, "alice-123")["type"] == "CHAT_IDENTITY_ACK"
        assert hello(wb, "bob-12345")["type"] == "CHAT_IDENTITY_ACK"
        wa.send_json({"type": "PEER_MESSAGE", "device_id": a, "request_id": "r1", "payload": {
            "to": "bob-12345", "from": "mallory", "message_id": "m1", "ciphertext": "opaque"
        }})
        incoming = wb.receive_json()
        assert incoming["payload"]["from"] == "alice-123"
        assert "to" not in incoming["payload"]
        ack = wa.receive_json()
        assert ack["type"] == "PEER_DELIVER" and ack["payload"]["delivered"] is True


def test_unknown_contact_is_not_enumerated(client):
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device, pairing_token(), "A")
        hello(ws, "alice-123")
        ws.send_json({"type": "PEER_MESSAGE", "device_id": device, "payload": {
            "to": "does-not-exist", "message_id": "m2", "ciphertext": "opaque"
        }})
        ack = ws.receive_json()
        assert ack["type"] == "PEER_DELIVER"
        assert ack["payload"] == {"message_id": "m2", "delivered": False}


def test_chat_identity_requires_device_handshake(client):
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"type": "CHAT_IDENTITY_HELLO", "payload": {
            "contact_id": "alice123", "public_key": "A" * 64,
        }})
        assert ws.receive_json()["payload"]["code"] == "NOT_AUTHENTICATED"


def test_call_signaling_routes_to_exact_peer(client):
    a, b = new_device_id(), new_device_id()
    with client.websocket_connect("/ws/device") as wa, client.websocket_connect("/ws/device") as wb:
        unpaired_hello(wa, a, pairing_token(), "A")
        unpaired_hello(wb, b, pairing_token(), "B")
        hello(wa, "alice-123"); hello(wb, "bob-12345")
        wa.send_json({"type": "CALL_OFFER", "device_id": a, "payload": {
            "to": "bob-12345", "call_id": "call-1", "sdp": "opaque-sdp"
        }})
        incoming = wb.receive_json()
        assert incoming["type"] == "CALL_OFFER"
        assert incoming["payload"]["from"] == "alice-123"


def test_oversized_peer_envelope_is_rejected(client):
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device, pairing_token(), "A")
        hello(ws, "alice-123")
        ws.send_json({"type": "PEER_MESSAGE", "device_id": device, "payload": {
            "to": "bob-12345", "message_id": "large", "ciphertext": "x" * 270_000
        }})
        error = ws.receive_json()
        assert error["type"] == "ERROR"
        assert error["payload"]["code"] == "MESSAGE_TOO_LARGE"
