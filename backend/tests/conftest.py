from __future__ import annotations

import re
import secrets
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

CSRF_RE = re.compile(r'name="csrf-token" content="([^"]+)"')
FORM_CSRF_RE = re.compile(r'name="csrf_token" value="([^"]+)"')


def make_settings(tmp_path: Path) -> Settings:
    db = (tmp_path / "ownerlink-test.db").as_posix()
    return Settings(
        _env_file=None,
        OWNERLINK_ADMIN_USERNAME="owner",
        OWNERLINK_ADMIN_PASSWORD="correct-horse-battery",
        OWNERLINK_SECRET_KEY="test-secret-key-32-chars-minimum",
        OWNERLINK_BASE_URL="http://testserver",
        DATABASE_URL=f"sqlite:///{db}",
        ENV="test",
    )


@pytest.fixture
def app(tmp_path: Path):
    return create_app(make_settings(tmp_path))


@pytest.fixture
def client(app):
    with TestClient(app) as c:
        yield c


def csrf_token(client: TestClient, path: str = "/login") -> str:
    page = client.get(path, follow_redirects=True)
    match = CSRF_RE.search(page.text) or FORM_CSRF_RE.search(page.text)
    assert match, f"csrf token missing on {path}"
    return match.group(1)


def login(client: TestClient, username: str = "owner", password: str = "correct-horse-battery") -> str:
    token = csrf_token(client, "/login")
    response = client.post(
        "/login",
        data={"username": username, "password": password, "csrf_token": token},
        follow_redirects=False,
    )
    assert response.status_code in {200, 303}
    return csrf_token(client, "/")


def pairing_token() -> str:
    return secrets.token_urlsafe(32)


def new_device_id() -> str:
    return str(uuid.uuid4())


def unpaired_hello(ws, device_id: str, token: str, name: str) -> dict:
    ws.send_json(
        {
            "type": "UNPAIRED_HELLO",
            "device_id": device_id,
            "payload": {"pairing_token": token, "name": name, "app_version": "1.0.0"},
        }
    )
    return ws.receive_json()
