from __future__ import annotations

import base64
import hashlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from conftest import new_device_id, pairing_token, unpaired_hello


def identity():
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    contact_id = base64.urlsafe_b64encode(hashlib.sha256(public).digest()[:18]).decode().rstrip("=")
    return private, base64.urlsafe_b64encode(public).decode().rstrip("="), contact_id


def hello(ws, device_id: str, value, name: str = "Alice"):
    private, public, contact_id = value
    ws.send_json({"v": 1, "type": "CHAT_CHALLENGE_REQUEST", "device_id": device_id, "payload": {}})
    challenge = ws.receive_json()["payload"]["challenge"]
    signature = private.sign(f"{challenge}:{device_id}".encode(), ec.ECDSA(hashes.SHA256()))
    ws.send_json({"v": 1, "type": "CHAT_IDENTITY_HELLO", "device_id": device_id, "payload": {
        "contact_id": contact_id, "public_key": public, "name": name, "challenge": challenge,
        "signature": base64.urlsafe_b64encode(signature).decode().rstrip("="),
    }})
    return ws.receive_json()


def connect_identity(ws, device_id: str, name: str):
    unpaired_hello(ws, device_id, pairing_token(), name)
    value = identity()
    assert hello(ws, device_id, value, name)["type"] == "CHAT_IDENTITY_ACK"
    return value[2]


def test_exact_contact_routing_binds_sender_to_socket(client):
    a, b = new_device_id(), new_device_id()
    with client.websocket_connect("/ws/device") as wa, client.websocket_connect("/ws/device") as wb:
        alice = connect_identity(wa, a, "Alice"); bob = connect_identity(wb, b, "Bob")
        wa.send_json({"v": 1, "type": "PEER_MESSAGE", "device_id": a, "request_id": "r1", "payload": {
            "to": bob, "from": "mallory", "message_id": "m1", "ciphertext": "opaque"
        }})
        incoming = wb.receive_json()
        assert incoming["payload"]["from"] == alice and "to" not in incoming["payload"]
        ack = wa.receive_json()
        assert ack["type"] == "PEER_DELIVER" and ack["payload"]["delivered"] is True


def test_unknown_contact_is_not_enumerated(client):
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        connect_identity(ws, device, "Alice")
        ws.send_json({"v": 1, "type": "PEER_MESSAGE", "device_id": device, "payload": {
            "to": "does-not-exist", "message_id": "m2", "ciphertext": "opaque"
        }})
        ack = ws.receive_json()
        assert ack["type"] == "PEER_DELIVER" and ack["payload"] == {"message_id": "m2", "delivered": False}


def test_chat_identity_requires_handshake_and_valid_proof(client):
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"v": 1, "type": "CHAT_IDENTITY_HELLO", "payload": {"contact_id": "alice123", "public_key": "A" * 64}})
        assert ws.receive_json()["payload"]["code"] == "NOT_AUTHENTICATED"
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device, pairing_token(), "Alice")
        value = identity()
        ws.send_json({"v": 1, "type": "CHAT_CHALLENGE_REQUEST", "device_id": device, "payload": {}})
        challenge = ws.receive_json()["payload"]["challenge"]
        ws.send_json({"v": 1, "type": "CHAT_IDENTITY_HELLO", "device_id": device, "payload": {
            "contact_id": value[2], "public_key": value[1], "name": "Alice", "challenge": challenge, "signature": "invalid"
        }})
        assert ws.receive_json()["payload"]["code"] == "CHAT_IDENTITY_PROOF_FAILED"


def test_call_and_peer_signaling_route_to_exact_peer(client):
    ids = [new_device_id() for _ in range(3)]
    with client.websocket_connect("/ws/device") as wa, client.websocket_connect("/ws/device") as wb, client.websocket_connect("/ws/device") as wc:
        alice = connect_identity(wa, ids[0], "Alice"); bob = connect_identity(wb, ids[1], "Bob"); carol = connect_identity(wc, ids[2], "Carol")
        wa.send_json({"v": 1, "type": "CALL_INVITE", "device_id": ids[0], "payload": {"to": bob, "call_id": "call-1"}})
        call = wb.receive_json(); assert call["type"] == "CALL_INVITE" and call["payload"]["from"] == alice
        wa.send_json({"v": 1, "type": "PEER_SIGNAL", "device_id": ids[0], "payload": {"to": bob, "from": carol, "signal": "offer", "sdp": "opaque"}})
        signal = wb.receive_json(); assert signal["type"] == "PEER_SIGNAL" and signal["payload"]["from"] == alice
        assert client.app.state.registry.contacts[carol].last_seen > 0


def test_exact_contact_lookup_and_presence(client):
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        contact = connect_identity(ws, device, "Alice")
        found = client.get(f"/api/contact/{contact}")
        assert found.status_code == 200 and found.json()["contact"]["name"] == "Alice"
        assert client.get("/api/contact/not-found-123").status_code == 404
        ws.send_json({"v": 1, "type": "PRESENCE_QUERY", "device_id": device, "payload": {"contact_id": contact}})
        presence = ws.receive_json(); assert presence["type"] == "PRESENCE_UPDATE" and presence["payload"]["online"] is True


def test_oversized_and_incompatible_frames_are_rejected(client):
    device = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        connect_identity(ws, device, "Alice")
        ws.send_json({"v": 1, "type": "PEER_MESSAGE", "device_id": device, "payload": {"to": "unknown-contact", "message_id": "large", "ciphertext": "x" * 270_000}})
        assert ws.receive_json()["payload"]["code"] == "MESSAGE_TOO_LARGE"
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"v": 99, "type": "UNPAIRED_HELLO", "device_id": new_device_id(), "payload": {}})
        assert ws.receive_json()["payload"]["code"] == "BAD_VERSION"
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json(["not", "an", "envelope"])
        assert ws.receive_json()["payload"]["code"] == "BAD_JSON"
    with client.websocket_connect("/ws/device") as ws:
        ws.send_json({"v": 1, "type": "UNPAIRED_HELLO", "device_id": new_device_id(), "payload": []})
        assert ws.receive_json()["payload"]["code"] == "BAD_PAYLOAD"
