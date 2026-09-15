"""Redis persistence for Render production; SQLite stays available for local development."""
from __future__ import annotations

import json
import secrets
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any

import httpx

from .hashing import hash_secret, secrets_equal
from .models import utcnow


@dataclass
class StoredOwner:
    id: int = 1
    username: str = ""
    password_hash: str = ""


@dataclass
class StoredDevice:
    device_id: str
    friendly_name: str
    pairing_token_hash: str | None = None
    pairing_consumed: bool = False
    device_session_hash: str | None = None
    revoked: bool = False
    created_at: datetime | None = None
    last_seen: datetime | None = None
    battery: int | None = None
    charging: bool | None = None


class RedisStore:
    """One small Upstash REST repository. No media or transfer bytes are stored."""
    prefix = "ownerlink:v1"

    def __init__(self, url: str, token: str):
        self.url, self.token = url.rstrip("/"), token
        self.client = httpx.Client(timeout=10.0, headers={"Authorization": f"Bearer {token}"})

    def __enter__(self): return self
    def __exit__(self, *_args): return False
    def close(self): pass

    def _cmd(self, *parts: str) -> Any:
        response = self.client.post(self.url, json=list(parts))
        response.raise_for_status()
        result = response.json()
        if result.get("error"): raise RuntimeError(f"Upstash Redis error: {result['error']}")
        return result.get("result")

    def _pipeline(self, commands: list[list[str]]) -> list[Any]:
        """Execute independent Redis commands in one Upstash HTTP round trip."""
        if not commands:
            return []
        response = self.client.post(f"{self.url}/pipeline", json=commands)
        response.raise_for_status()
        rows = response.json()
        if not isinstance(rows, list):
            raise RuntimeError("Invalid Upstash pipeline response")
        results: list[Any] = []
        for row in rows:
            if not isinstance(row, dict) or row.get("error"):
                raise RuntimeError(f"Upstash Redis pipeline error: {(row or {}).get('error', 'invalid response')}")
            results.append(row.get("result"))
        return results

    def ping(self) -> bool: return self._cmd("PING") == "PONG"
    def _key(self, suffix: str) -> str: return f"{self.prefix}:{suffix}"
    def _get(self, key: str) -> dict | None:
        raw = self._cmd("GET", key)
        return json.loads(raw) if raw else None
    def _set(self, key: str, value: dict) -> None:
        self._cmd("SET", key, json.dumps(value, separators=(",", ":")))

    def get_owner(self) -> StoredOwner | None:
        value = self._get(self._key("owner"))
        return StoredOwner(**value) if value else None
    def save_owner(self, owner: StoredOwner) -> None: self._set(self._key("owner"), asdict(owner))

    def get_device(self, device_id: str) -> StoredDevice | None:
        value = self._get(self._key(f"device:{device_id}"))
        if not value: return None
        for field in ("created_at", "last_seen"):
            if value.get(field): value[field] = datetime.fromisoformat(value[field])
        return StoredDevice(**value)

    def save_device(self, device: StoredDevice) -> None:
        value = asdict(device)
        for field in ("created_at", "last_seen"):
            if value[field] is not None: value[field] = value[field].isoformat()
        self._set(self._key(f"device:{device.device_id}"), value)
        self._cmd("SADD", self._key("devices"), device.device_id)

    def active_devices(self) -> list[StoredDevice]:
        ids = self._cmd("SMEMBERS", self._key("devices")) or []
        return sorted((d for d in (self.get_device(str(i)) for i in ids) if d and d.pairing_consumed and not d.revoked), key=lambda d: d.friendly_name.lower())

    def upsert_unpaired(self, device_id: str, token: str, name: str) -> StoredDevice:
        current = self.get_device(device_id)
        device = current or StoredDevice(device_id, name.strip()[:128] or "Android Device", created_at=utcnow())
        device.pairing_token_hash, device.pairing_consumed, device.device_session_hash, device.revoked = hash_secret(token), False, None, False
        device.friendly_name, device.last_seen = name.strip()[:128] or device.friendly_name, utcnow()
        self.save_device(device)
        return device

    def pair_device(self, device_id: str, token: str, name: str | None) -> tuple[StoredDevice, str]:
        from .devices import PairingError
        device = self.get_device(device_id)
        if not device: raise PairingError("Unknown device. Open the OwnerLink app so it can connect first.", "UNKNOWN_DEVICE")
        if device.pairing_consumed: raise PairingError("This pairing QR was already used.", "TOKEN_CONSUMED")
        if not device.pairing_token_hash or not secrets_equal(device.pairing_token_hash, hash_secret(token)):
            raise PairingError("Pairing token did not match.", "TOKEN_MISMATCH")
        session = secrets.token_urlsafe(32)
        device.device_session_hash, device.pairing_consumed, device.revoked, device.last_seen = hash_secret(session), True, False, utcnow()
        if name and name.strip(): device.friendly_name = name.strip()[:128]
        self.save_device(device)
        return device, session

    def authenticate_device(self, device_id: str, token: str) -> StoredDevice:
        from .devices import PairingError
        device = self.get_device(device_id)
        if not device: raise PairingError("Unknown device", "UNKNOWN_DEVICE")
        if device.revoked or not device.pairing_consumed or not device.device_session_hash: raise PairingError("Device is not paired", "NOT_PAIRED")
        if not token or not secrets_equal(device.device_session_hash, hash_secret(token)): raise PairingError("Invalid device session", "AUTH_FAILED")
        device.last_seen = utcnow(); self.save_device(device); return device

    def touch_device(self, device_id: str, battery: int | None, charging: bool | None) -> None:
        device = self.get_device(device_id)
        if not device: return
        device.last_seen = utcnow()
        if battery is not None: device.battery = battery
        if charging is not None: device.charging = charging
        self.save_device(device)

    def save_contact(self, contact_id: str, public_key: str, name: str) -> None:
        self._set(self._key(f"chat:contact:{contact_id}"), {
            "contact_id": contact_id, "public_key": public_key, "name": name[:80],
        })

    def get_contact(self, contact_id: str) -> dict | None:
        return self._get(self._key(f"chat:contact:{contact_id}"))
