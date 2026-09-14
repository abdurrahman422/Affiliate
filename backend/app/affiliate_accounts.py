from __future__ import annotations

import base64
import json
import re
import secrets
import uuid
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from sqlalchemy.exc import IntegrityError

from .affiliate import AffiliateError, now_iso
from .hashing import hash_password, hash_secret, secrets_equal, verify_password
from .models import AffiliateSession, AffiliateUser, utcnow
from .storage import RedisStore


EDUCATION = {"SSC", "HSC", "Diploma", "Undergraduate", "Graduate", "Postgraduate", "Other"}
WITHDRAWAL = {"bKash", "Nagad"}


def normalize_site_address(value: Any) -> str:
    raw = str(value or "").strip()
    if not raw: return ""
    without_scheme = re.sub(r"^https?://", "", raw, flags=re.IGNORECASE).lstrip("/")
    parsed = urlsplit("https://" + without_scheme)
    if not parsed.hostname or "." not in parsed.hostname or parsed.username or parsed.password:
        raise AffiliateError("Enter a valid site address", "INVALID_SITE_ADDRESS")
    return urlunsplit(("https", parsed.netloc, parsed.path, parsed.query, parsed.fragment))[:300]


def normalize_bd_phone(value: Any) -> str:
    digits = re.sub(r"\D", "", str(value or ""))
    if digits.startswith("880"): digits = "0" + digits[3:]
    if not re.fullmatch(r"01[3-9]\d{8}", digits):
        raise AffiliateError("Enter a valid Bangladesh phone number", "INVALID_PHONE")
    return "+880" + digits[1:]


def validate_registration(raw: dict[str, Any], require_photo: bool = True) -> dict[str, Any]:
    name = str(raw.get("full_name") or "").strip()
    if not 2 <= len(name) <= 100: raise AffiliateError("Full name must be 2 to 100 characters", "INVALID_NAME")
    phone = normalize_bd_phone(raw.get("phone_number"))
    try: age = int(raw.get("age"))
    except (TypeError, ValueError): raise AffiliateError("Enter a valid age", "INVALID_AGE")
    if age < 13 or age > 100: raise AffiliateError("Age must be between 13 and 100", "INVALID_AGE")
    education = str(raw.get("education") or "").strip()
    if education not in EDUCATION: raise AffiliateError("Choose a valid educational qualification", "INVALID_EDUCATION")
    address, site = str(raw.get("address") or "").strip(), normalize_site_address(raw.get("site_address"))
    if not 3 <= len(address) <= 500: raise AffiliateError("Address is required", "INVALID_ADDRESS")
    # Site address is optional. The Android onboarding no longer asks normal users
    # for a server/site URL because the production endpoint is app-configured.
    if len(site) > 300: raise AffiliateError("Site address is too long", "INVALID_SITE_ADDRESS")
    method = str(raw.get("withdrawal_method") or "").strip()
    if method not in WITHDRAWAL: raise AffiliateError("Choose bKash or Nagad", "INVALID_WITHDRAWAL_METHOD")
    withdrawal = normalize_bd_phone(raw.get("withdrawal_number"))
    password = str(raw.get("password") or "")
    if len(password) < 8 or not re.search(r"[A-Za-z]", password) or not re.search(r"\d", password):
        raise AffiliateError("Password must contain at least 8 characters with letters and numbers", "WEAK_PASSWORD")
    if raw.get("confirm_password") is not None and password != str(raw.get("confirm_password")):
        raise AffiliateError("Passwords do not match", "PASSWORD_MISMATCH")
    photo_data, photo_mime = _photo(raw.get("profile_photo_base64"), raw.get("profile_photo_mime"), require_photo)
    return {"display_name": name, "phone_number": phone, "age": age, "education": education,
            "address": address, "site_address": site, "withdrawal_method": method,
            "withdrawal_number": withdrawal, "password_hash": hash_password(password),
            "profile_photo_data": photo_data, "profile_photo_mime": photo_mime}


def _photo(value: Any, mime: Any, required: bool) -> tuple[str | None, str | None]:
    text = str(value or "").strip()
    if not text:
        if required: raise AffiliateError("Profile photo is required", "PROFILE_PHOTO_REQUIRED")
        return None, None
    media = str(mime or "").lower()
    if media not in {"image/jpeg", "image/png", "image/webp"}: raise AffiliateError("Profile photo format is not supported", "INVALID_PROFILE_PHOTO")
    try: decoded = base64.b64decode(text, validate=True)
    except Exception: raise AffiliateError("Profile photo is invalid", "INVALID_PROFILE_PHOTO")
    if not 1 <= len(decoded) <= 512 * 1024: raise AffiliateError("Profile photo must be under 512 KB", "INVALID_PROFILE_PHOTO")
    return text, media


def mask_phone(value: str | None) -> str:
    if not value: return ""
    digits = value.replace("+880", "0")
    return digits[:2] + "******" + digits[-3:]


class AffiliateAccountService:
    def __init__(self, db, session_seconds: int = 60 * 60 * 24 * 30):
        self.db, self.redis, self.session_seconds = db, isinstance(db, RedisStore), session_seconds

    def _key(self, suffix: str) -> str: return self.db._key(f"affiliate:account:{suffix}")

    def register(self, raw: dict[str, Any], device_id: str = "") -> tuple[dict[str, Any], str]:
        values = validate_registration(raw)
        user_id, stamp = str(uuid.uuid4()), now_iso()
        if self.redis:
            phone_key = self._key("phone:" + values["phone_number"])
            record = {"user_id": user_id, **values, "device_id": device_id, "status": "ACTIVE", "account_status": "ACTIVE",
                      "registered": True, "balance_milli": 0, "quality_score": 100, "created_at": stamp, "updated_at": stamp}
            created = self.db._cmd("EVAL", "if redis.call('EXISTS',KEYS[1])==1 then return 0 end redis.call('SET',KEYS[1],ARGV[1]); redis.call('SET',KEYS[2],ARGV[2]); redis.call('SET',KEYS[3],ARGV[2]); redis.call('SADD',KEYS[4],ARGV[1]); return 1", "4", phone_key, self._key("user:" + user_id), self.db._key("affiliate:user:" + user_id), self._key("users"), user_id, json.dumps(record, separators=(",", ":")))
            if int(created or 0) != 1: raise AffiliateError("An account already exists for this phone number", "PHONE_EXISTS", 409)
        else:
            row = AffiliateUser(user_id=user_id, display_name=values["display_name"], device_id=device_id,
                phone_number=values["phone_number"], password_hash=values["password_hash"], age=values["age"],
                education=values["education"], address=values["address"], site_address=values["site_address"],
                withdrawal_method=values["withdrawal_method"], withdrawal_number=values["withdrawal_number"],
                profile_photo_data=values["profile_photo_data"], profile_photo_mime=values["profile_photo_mime"], registered=True)
            self.db.add(row)
            try: self.db.commit()
            except IntegrityError:
                self.db.rollback(); raise AffiliateError("An account already exists for this phone number", "PHONE_EXISTS", 409)
        token = self._issue(user_id)
        return self.get(user_id), token

    def login(self, phone: Any, password: Any) -> tuple[dict[str, Any], str]:
        normalized = normalize_bd_phone(phone)
        user = self._by_phone(normalized)
        if not user or not verify_password(str(password or ""), str(user.get("password_hash") or "")):
            raise AffiliateError("Phone number or password is incorrect", "INVALID_CREDENTIALS", 401)
        if user.get("status") != "ACTIVE": raise AffiliateError("This account is not active", "ACCOUNT_INACTIVE", 403)
        return self.public(user), self._issue(user["user_id"])

    def authenticate(self, token: str) -> dict[str, Any] | None:
        digest = hash_secret(token) if token else ""
        if self.redis:
            session = self.db._get(self._key("session:" + digest)) if digest else None
            if not session or session.get("revoked") or session.get("expires_at", "") <= now_iso(): return None
            return self.get(session["user_id"], include_private=True)
        row = self.db.query(AffiliateSession).filter(AffiliateSession.token_hash == digest, AffiliateSession.revoked.is_(False)).one_or_none()
        if not row or row.expires_at <= utcnow(): return None
        return self.get(row.user_id, include_private=True)

    def logout(self, token: str) -> None:
        digest = hash_secret(token) if token else ""
        if self.redis:
            value = self.db._get(self._key("session:" + digest)) if digest else None
            if value: value["revoked"] = True; self.db._set(self._key("session:" + digest), value)
        else:
            row = self.db.query(AffiliateSession).filter(AffiliateSession.token_hash == digest).one_or_none()
            if row: row.revoked = True; self.db.commit()

    def update(self, user_id: str, raw: dict[str, Any]) -> dict[str, Any]:
        allowed = {"display_name", "age", "education", "address", "site_address", "withdrawal_method", "withdrawal_number", "profile_photo_data", "profile_photo_mime"}
        current = self.get(user_id, include_private=True)
        if not current: raise AffiliateError("User not found", "NOT_FOUND", 404)
        patch: dict[str, Any] = {}
        if "full_name" in raw:
            name = str(raw["full_name"]).strip()
            if not 2 <= len(name) <= 100: raise AffiliateError("Full name must be 2 to 100 characters", "INVALID_NAME")
            patch["display_name"] = name
        if "age" in raw:
            try: age = int(raw["age"])
            except (TypeError, ValueError): raise AffiliateError("Enter a valid age", "INVALID_AGE")
            if not 13 <= age <= 100: raise AffiliateError("Age must be between 13 and 100", "INVALID_AGE")
            patch["age"] = age
        for api, field, options, limit in (("education", "education", EDUCATION, 80), ("withdrawal_method", "withdrawal_method", WITHDRAWAL, 16)):
            if api in raw:
                value = str(raw[api]).strip()
                if value not in options: raise AffiliateError(f"Invalid {api.replace('_', ' ')}", "INVALID_PROFILE")
                patch[field] = value[:limit]
        for field, limit in (("address", 500), ("site_address", 300)):
            if field in raw:
                value = str(raw[field]).strip()
                if field == "address" and len(value) < 2: raise AffiliateError("Address is required", "INVALID_PROFILE")
                patch[field] = normalize_site_address(value) if field == "site_address" else value[:limit]
        if "withdrawal_number" in raw: patch["withdrawal_number"] = normalize_bd_phone(raw["withdrawal_number"])
        if raw.get("profile_photo_base64"):
            photo, mime = _photo(raw["profile_photo_base64"], raw.get("profile_photo_mime"), True)
            patch.update(profile_photo_data=photo, profile_photo_mime=mime)
        patch["updated_at"] = now_iso()
        if self.redis:
            current.update(patch); self.db._set(self._key("user:" + user_id), current); self.db._set(self.db._key("affiliate:user:" + user_id), current)
        else:
            row = self.db.get(AffiliateUser, user_id)
            for key, value in patch.items(): setattr(row, key, utcnow() if key == "updated_at" else value)
            self.db.commit()
        return self.get(user_id)

    def change_password(self, user_id: str, current_password: Any, new_password: Any, confirm_password: Any) -> None:
        current = self.get(user_id, include_private=True)
        if not current or not verify_password(str(current_password or ""), str(current.get("password_hash") or "")):
            raise AffiliateError("Current password is incorrect", "INVALID_PASSWORD", 401)
        password = str(new_password or "")
        if len(password) < 8 or not re.search(r"[A-Za-z]", password) or not re.search(r"\d", password):
            raise AffiliateError("New password must contain at least 8 characters with letters and numbers", "WEAK_PASSWORD")
        if password != str(confirm_password or ""):
            raise AffiliateError("Passwords do not match", "PASSWORD_MISMATCH")
        password_hash = hash_password(password)
        if self.redis:
            current.update(password_hash=password_hash, updated_at=now_iso())
            self.db._set(self._key("user:" + user_id), current)
            self.db._set(self.db._key("affiliate:user:" + user_id), current)
        else:
            row = self.db.get(AffiliateUser, user_id)
            row.password_hash, row.updated_at = password_hash, utcnow()
            self.db.commit()

    def users(self) -> list[dict[str, Any]]:
        if self.redis:
            ids = self.db._cmd("SMEMBERS", self._key("users")) or []
            rows = [self.get(str(value), include_private=True) for value in ids]
        else: rows = [self._row(row) for row in self.db.query(AffiliateUser).filter(AffiliateUser.registered.is_(True)).all()]
        return [self.admin(row) for row in rows if row]

    def get(self, user_id: str, include_private: bool = False) -> dict[str, Any] | None:
        if self.redis: value = self.db._get(self._key("user:" + user_id))
        else:
            row = self.db.get(AffiliateUser, user_id); value = self._row(row) if row else None
        return value if include_private else self.public(value) if value else None

    def _by_phone(self, phone: str) -> dict[str, Any] | None:
        if self.redis:
            user_id = self.db._cmd("GET", self._key("phone:" + phone)); return self.get(str(user_id), True) if user_id else None
        row = self.db.query(AffiliateUser).filter(AffiliateUser.phone_number == phone, AffiliateUser.registered.is_(True)).one_or_none()
        return self._row(row) if row else None

    def _issue(self, user_id: str) -> str:
        token, digest, expiry = secrets.token_urlsafe(48), "", utcnow() + timedelta(seconds=self.session_seconds)
        digest = hash_secret(token)
        if self.redis: self.db._set(self._key("session:" + digest), {"user_id": user_id, "revoked": False, "expires_at": expiry.isoformat()})
        else:
            self.db.add(AffiliateSession(session_id=str(uuid.uuid4()), user_id=user_id, token_hash=digest, expires_at=expiry)); self.db.commit()
        return token

    @staticmethod
    def _row(row: AffiliateUser) -> dict[str, Any]:
        return {column.name: getattr(row, column.name) for column in AffiliateUser.__table__.columns}

    @staticmethod
    def public(value: dict[str, Any]) -> dict[str, Any]:
        return {"user_id": value["user_id"], "full_name": value.get("display_name", ""), "phone_number": value.get("phone_number", ""),
                "age": value.get("age"), "education": value.get("education", ""), "address": value.get("address", ""),
                "site_address": value.get("site_address", ""), "withdrawal_method": value.get("withdrawal_method", ""),
                "withdrawal_number_masked": mask_phone(value.get("withdrawal_number")), "account_status": value.get("status", "ACTIVE"),
                "profile_photo_url": f"/api/users/{value['user_id']}/photo" if value.get("profile_photo_data") else None,
                "created_at": str(value.get("created_at") or ""), "updated_at": str(value.get("updated_at") or "")}

    def admin(self, value: dict[str, Any]) -> dict[str, Any]:
        result = self.public(value); result["withdrawal_number_masked"] = mask_phone(value.get("withdrawal_number")); result["balance_milli"] = int(value.get("balance_milli", 0)); return result
