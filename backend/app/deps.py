from __future__ import annotations

from fastapi import Depends, HTTPException, Request, status
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session

from .auth import current_owner, ensure_csrf, require_csrf, require_owner
from .models import Device, Owner
from . import devices as device_ops


def templates_ctx(request: Request, extra: dict | None = None) -> dict:
    ctx = {
        "request": request,
        "csrf_token": ensure_csrf(request),
        "owner_name": None,
    }
    if extra:
        ctx.update(extra)
    return ctx


def owner_or_redirect(request: Request, db: Session) -> Owner | None:
    return current_owner(request, db)


def api_owner(request: Request, db: Session) -> Owner:
    try:
        return require_owner(request, db)
    except HTTPException:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")


def csrf_post(request: Request) -> None:
    require_csrf(request)


def load_active_device(db: Session, device_id: str) -> Device:
    try:
        return device_ops.require_active(db, device_id)
    except device_ops.PairingError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=exc.message) from exc
