from __future__ import annotations

import logging
import secrets
from datetime import datetime

from sqlalchemy.orm import Session

from .hashing import hash_secret, secrets_equal
from .models import Device, utcnow
from .util import is_uuid, normalize_base_url, pairing_token_entropy_ok
from .storage import RedisStore

log = logging.getLogger("ownerlink.devices")


class PairingError(Exception):
    def __init__(self, message: str, code: str = "PAIRING_FAILED"):
        super().__init__(message)
        self.message = message
        self.code = code


def get_device(db: Session, device_id: str) -> Device | None:
    if isinstance(db, RedisStore):
        return db.get_device(device_id)
    return db.query(Device).filter(Device.device_id == device_id).one_or_none()


def list_active_devices(db: Session) -> list[Device]:
    if isinstance(db, RedisStore):
        return db.active_devices()
    return (
        db.query(Device)
        .filter(Device.pairing_consumed.is_(True), Device.revoked.is_(False))
        .order_by(Device.friendly_name.asc())
        .all()
    )


def find_unpaired_by_token(db: Session, token: str) -> Device | None:
    """Resolve a short one-time redeem code without exposing a device identifier."""
    digest = hash_secret(token)
    if isinstance(db, RedisStore):
        ids = db._cmd("SMEMBERS", db._key("devices")) or []
        for device_id in ids:
            device = db.get_device(str(device_id))
            if device and not device.pairing_consumed and device.pairing_token_hash and secrets_equal(device.pairing_token_hash, digest):
                return device
        return None
    return db.query(Device).filter(Device.pairing_token_hash == digest, Device.pairing_consumed.is_(False)).one_or_none()


def upsert_unpaired(db: Session, device_id: str, pairing_token: str, name: str) -> Device:
    if isinstance(db, RedisStore):
        if not is_uuid(device_id) or not pairing_token_entropy_ok(pairing_token):
            raise PairingError("Invalid unpaired device", "INVALID_DEVICE")
        return db.upsert_unpaired(device_id, pairing_token, name)
    if not is_uuid(device_id):
        raise PairingError("Invalid device id", "INVALID_DEVICE")
    if not pairing_token_entropy_ok(pairing_token):
        raise PairingError("Pairing token is too short", "INVALID_TOKEN")

    token_hash = hash_secret(pairing_token)
    device = get_device(db, device_id)
    friendly = (name or "").strip()[:128] or "Android Device"

    if device is None:
        device = Device(
            device_id=device_id,
            friendly_name=friendly,
            pairing_token_hash=token_hash,
            pairing_consumed=False,
            device_session_hash=None,
            revoked=False,
            last_seen=utcnow(),
        )
        db.add(device)
        db.commit()
        db.refresh(device)
        log.info("pending unpaired device registered")
        return device

    if device.pairing_consumed and not device.revoked and device.device_session_hash:
        raise PairingError("Device is already paired. Remove it from the dashboard to re-pair.", "ALREADY_PAIRED")

    device.pairing_token_hash = token_hash
    device.pairing_consumed = False
    device.device_session_hash = None
    device.revoked = False
    if friendly:
        device.friendly_name = friendly
    device.last_seen = utcnow()
    db.commit()
    db.refresh(device)
    return device


def pair_device(
    db: Session,
    device_id: str,
    pairing_token: str,
    expected_base_url: str,
    qr_server: str,
    friendly_name: str | None,
    device_currently_connected: bool,
) -> tuple[Device, str]:
    if isinstance(db, RedisStore):
        if not is_uuid(device_id) or not pairing_token_entropy_ok(pairing_token):
            raise PairingError("Invalid device or pairing token", "INVALID_DEVICE")
        if not device_currently_connected:
            raise PairingError("Device is not online. Keep the OwnerLink app open on the phone and try again.", "DEVICE_OFFLINE")
        return db.pair_device(device_id, pairing_token, friendly_name)
    if not is_uuid(device_id):
        raise PairingError("Invalid device id", "INVALID_DEVICE")
    if not pairing_token_entropy_ok(pairing_token):
        raise PairingError("Invalid pairing token", "INVALID_TOKEN")
    if not device_currently_connected:
        raise PairingError(
            "Device is not online. Keep the OwnerLink app open on the phone and try again.",
            "DEVICE_OFFLINE",
        )

    expected = normalize_base_url(expected_base_url)
    got = normalize_base_url(qr_server)
    if expected and got and expected != got:
        # Phone already connected to this server; allow LAN vs localhost URL mismatch.
        log.info("pairing QR server field differed from OWNERLINK_BASE_URL; allowing because device is connected here")

    device = get_device(db, device_id)
    if device is None:
        raise PairingError("Unknown device. Open the OwnerLink app so it can connect first.", "UNKNOWN_DEVICE")
    if device.pairing_consumed:
        raise PairingError("This pairing QR was already used.", "TOKEN_CONSUMED")
    if not device.pairing_token_hash:
        raise PairingError("Device is not ready to pair.", "NOT_READY")
    if not secrets_equal(device.pairing_token_hash, hash_secret(pairing_token)):
        raise PairingError("Pairing token did not match.", "TOKEN_MISMATCH")

    session_token = secrets.token_urlsafe(32)
    device.device_session_hash = hash_secret(session_token)
    device.pairing_consumed = True
    device.revoked = False
    if friendly_name and friendly_name.strip():
        device.friendly_name = friendly_name.strip()[:128]
    device.last_seen = utcnow()
    db.commit()
    db.refresh(device)
    log.info("device paired successfully")
    return device, session_token


def authenticate_session(db: Session, device_id: str, session_token: str) -> Device:
    if isinstance(db, RedisStore):
        return db.authenticate_device(device_id, session_token)
    device = get_device(db, device_id)
    if device is None:
        raise PairingError("Unknown device", "UNKNOWN_DEVICE")
    if device.revoked or not device.pairing_consumed:
        raise PairingError("Device is not paired", "NOT_PAIRED")
    if not device.device_session_hash:
        raise PairingError("Device session is missing", "NOT_PAIRED")
    if not session_token or not secrets_equal(device.device_session_hash, hash_secret(session_token)):
        raise PairingError("Invalid device session", "AUTH_FAILED")
    device.last_seen = utcnow()
    db.commit()
    return device


def touch_device(db: Session, device_id: str, battery: int | None = None, charging: bool | None = None) -> None:
    if isinstance(db, RedisStore):
        db.touch_device(device_id, battery, charging)
        return
    device = get_device(db, device_id)
    if device is None:
        return
    device.last_seen = utcnow()
    if battery is not None:
        device.battery = int(battery)
    if charging is not None:
        device.charging = bool(charging)
    db.commit()


def rename_device(db: Session, device_id: str, name: str) -> Device:
    if isinstance(db, RedisStore):
        device = require_active(db, device_id)
        device.friendly_name = name.strip()[:128] or device.friendly_name
        db.save_device(device)
        return device
    device = require_active(db, device_id)
    device.friendly_name = name.strip()[:128] or device.friendly_name
    db.commit()
    db.refresh(device)
    return device


def require_active(db: Session, device_id: str) -> Device:
    device = get_device(db, device_id)
    if device is None or device.revoked or not device.pairing_consumed:
        raise PairingError("Device not found", "NOT_FOUND")
    return device


def revoke_device(db: Session, device_id: str) -> Device:
    if isinstance(db, RedisStore):
        device = require_active(db, device_id)
        device.revoked, device.device_session_hash, device.pairing_consumed = True, None, True
        db.save_device(device)
        return device
    device = require_active(db, device_id)
    device.revoked = True
    device.device_session_hash = None
    device.pairing_consumed = True
    db.commit()
    db.refresh(device)
    log.info("device revoked")
    return device


def last_seen_iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.isoformat(sep=" ", timespec="seconds") + " UTC"
