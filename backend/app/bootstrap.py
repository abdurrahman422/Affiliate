from __future__ import annotations

import logging
import sys

from sqlalchemy.orm import Session

from .config import Settings
from .hashing import hash_password
from .models import Owner
from .storage import RedisStore, StoredOwner

log = logging.getLogger("ownerlink.bootstrap")


def validate_runtime_settings(settings: Settings) -> None:
    secret = (settings.OWNERLINK_SECRET_KEY or "").strip()
    if settings.is_production:
        if len(secret) < 32:
            log.error("OWNERLINK_SECRET_KEY must be at least 32 characters in production")
            sys.exit(1)
        if not settings.OWNERLINK_BASE_URL.lower().startswith("https://"):
            log.error("OWNERLINK_BASE_URL must be https:// in production")
            sys.exit(1)
    elif len(secret) < 16:
        log.warning("OWNERLINK_SECRET_KEY is short; set a 32+ character secret before production")
    if settings.is_production and not settings.uses_redis:
        log.error("Render production requires STORAGE_BACKEND=redis")
        sys.exit(1)
    if settings.uses_redis and (not settings.UPSTASH_REDIS_REST_URL or not settings.UPSTASH_REDIS_REST_TOKEN):
        log.error("Redis storage requires UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN")
        sys.exit(1)


def bootstrap_owner(db: Session, settings: Settings) -> None:
    if isinstance(db, RedisStore):
        existing = db.get_owner()
        if existing is not None:
            return
        username, password = (settings.OWNERLINK_ADMIN_USERNAME or "").strip(), settings.OWNERLINK_ADMIN_PASSWORD or ""
        if len(username) < 3 or len(password) < 12 or password.lower() in {"admin", "password", "admin/admin", "changeme"}:
            log.error("A first boot needs a username (3+ chars) and strong password (12+ chars)")
            sys.exit(1)
        db.save_owner(StoredOwner(username=username, password_hash=hash_password(password)))
        log.warning("Owner account created in Upstash Redis. Remove OWNERLINK_ADMIN_PASSWORD after boot.")
        return
    existing = db.query(Owner).first()
    if existing is not None:
        log.info("Owner account already exists; bootstrap skipped")
        return

    username = (settings.OWNERLINK_ADMIN_USERNAME or "").strip()
    password = settings.OWNERLINK_ADMIN_PASSWORD or ""
    if not username or not password:
        log.error(
            "No owner account exists. Set OWNERLINK_ADMIN_USERNAME and OWNERLINK_ADMIN_PASSWORD for first boot."
        )
        sys.exit(1)
    if len(username) < 3:
        log.error("OWNERLINK_ADMIN_USERNAME must be at least 3 characters")
        sys.exit(1)
    if len(password) < 12:
        log.error("OWNERLINK_ADMIN_PASSWORD must be at least 12 characters")
        sys.exit(1)
    if password.lower() in {"admin", "password", "admin/admin", "changeme"}:
        log.error("OWNERLINK_ADMIN_PASSWORD is too weak")
        sys.exit(1)

    owner = Owner(username=username, password_hash=hash_password(password))
    db.add(owner)
    db.commit()
    log.warning(
        "Owner account created from environment for user %s. "
        "Remove OWNERLINK_ADMIN_PASSWORD from the environment after first boot.",
        username,
    )
