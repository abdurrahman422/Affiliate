from __future__ import annotations

import secrets
import time
from collections import defaultdict

from fastapi import HTTPException, Request, status
from sqlalchemy.orm import Session
from starlette.responses import RedirectResponse

from .config import Settings
from .hashing import verify_password
from .models import Owner
from .storage import RedisStore


class LoginLimiter:
    def __init__(self, max_attempts: int, window_seconds: int):
        self.max_attempts = max_attempts
        self.window_seconds = window_seconds
        self._hits: dict[str, list[float]] = defaultdict(list)

    def too_many(self, key: str) -> bool:
        now = time.time()
        bucket = [t for t in self._hits[key] if now - t < self.window_seconds]
        self._hits[key] = bucket
        return len(bucket) >= self.max_attempts

    def record(self, key: str) -> None:
        self._hits[key].append(time.time())

    def clear(self, key: str) -> None:
        self._hits.pop(key, None)


def ensure_csrf(request: Request) -> str:
    token = request.session.get("csrf")
    if not token:
        token = secrets.token_urlsafe(32)
        request.session["csrf"] = token
    return token


def require_csrf(request: Request) -> None:
    expected = request.session.get("csrf")
    provided = request.headers.get("X-CSRF-Token")
    if not provided:
        provided = request.query_params.get("csrf")
    if expected and provided and secrets.compare_digest(str(expected), str(provided)):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid CSRF token")


def current_owner(request: Request, db: Session) -> Owner | None:
    owner_id = request.session.get("owner_id")
    if not owner_id:
        return None
    if isinstance(db, RedisStore):
        owner = db.get_owner()
        return owner if owner is not None and owner.id == owner_id else None
    return db.get(Owner, owner_id)


def require_owner(request: Request, db: Session) -> Owner:
    owner = current_owner(request, db)
    if owner is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")
    return owner


def login_owner(request: Request, owner: Owner) -> None:
    request.session["owner_id"] = owner.id
    request.session["csrf"] = secrets.token_urlsafe(32)


def logout_owner(request: Request) -> None:
    request.session.clear()


def authenticate(db: Session, username: str, password: str) -> Owner | None:
    if isinstance(db, RedisStore):
        owner = db.get_owner()
        return owner if owner and owner.username == username and verify_password(password, owner.password_hash) else None
    owner = db.query(Owner).filter(Owner.username == username).one_or_none()
    if owner is None:
        return None
    if not verify_password(password, owner.password_hash):
        return None
    return owner


def redirect_login() -> RedirectResponse:
    return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)


def cookie_kwargs(settings: Settings) -> dict:
    return {
        "httponly": True,
        "samesite": "lax",
        "secure": settings.cookie_secure,
        "max_age": settings.SESSION_MAX_AGE,
    }
