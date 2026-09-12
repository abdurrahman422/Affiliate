from __future__ import annotations

import base64
import hashlib
import hmac
import time

from .config import Settings


def _urls(raw: str, allowed: tuple[str, ...]) -> list[str]:
    values = [item.strip() for item in raw.split(",") if item.strip()]
    return [item for item in values if item.lower().startswith(allowed)][:8]


def create_ice_configuration(settings: Settings, subject: str, now: int | None = None) -> dict:
    """Create a WebRTC configuration without exposing the Coturn shared secret.

    Coturn's REST API convention uses an expiry-prefixed username and a
    base64 HMAC-SHA1 credential. ICE still tries host/STUN candidates before a
    relay unless a client explicitly forces relay-only transport.
    """
    ice_servers: list[dict] = []
    stun_urls = _urls(settings.STUN_URLS, ("stun:", "stuns:"))
    if stun_urls:
        ice_servers.append({"urls": stun_urls})

    turn_urls = _urls(settings.TURN_URLS, ("turn:", "turns:"))
    expires_at = 0
    if turn_urls and settings.TURN_SHARED_SECRET:
        ttl = min(86400, max(60, settings.TURN_CREDENTIAL_TTL))
        expires_at = int(now if now is not None else time.time()) + ttl
        safe_subject = "".join(ch for ch in subject if ch.isalnum() or ch in "-_")[:64] or "device"
        username = f"{expires_at}:{safe_subject}"
        digest = hmac.new(settings.TURN_SHARED_SECRET.encode(), username.encode(), hashlib.sha1).digest()
        ice_servers.append({
            "urls": turn_urls,
            "username": username,
            "credential": base64.b64encode(digest).decode(),
        })
    return {"ice_servers": ice_servers, "expires_at": expires_at, "realm": settings.TURN_REALM[:128]}
