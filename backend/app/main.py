from __future__ import annotations

import asyncio
import json
import logging
import secrets
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Form, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from starlette.middleware.sessions import SessionMiddleware
from starlette.websockets import WebSocketState

from . import devices as device_ops
from . import protocol as P
from .auth import (
    LoginLimiter,
    authenticate,
    current_owner,
    ensure_csrf,
    login_owner,
    logout_owner,
    require_csrf,
)
from .bootstrap import bootstrap_owner, validate_runtime_settings
from .config import Settings, get_settings
from .db import init_db, make_engine, session_factory
from .hashing import hash_password, verify_password
from .models import Owner
from .registry import CommandError, DeviceOffline, Registry
from .storage import RedisStore
from .util import content_disposition, is_uuid, pairing_token_entropy_ok

log = logging.getLogger("ownerlink")

BASE_DIR = Path(__file__).resolve().parent
TEMPLATE_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"


def setup_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    logging.getLogger("uvicorn.access").setLevel(logging.INFO)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def parse_csrf_from_form(form_value: str | None, request: Request) -> None:
    provided = form_value or request.headers.get("X-CSRF-Token")
    expected = request.session.get("csrf")
    if expected and provided and secrets.compare_digest(str(expected), str(provided)):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid CSRF token")


def create_app(settings: Settings | None = None) -> FastAPI:
    setup_logging()
    settings = settings or get_settings()
    validate_runtime_settings(settings)
    # Render production uses durable Upstash metadata. Local/test installations keep SQLite.
    if settings.uses_redis:
        store = RedisStore(settings.UPSTASH_REDIS_REST_URL, settings.UPSTASH_REDIS_REST_TOKEN)
        if not store.ping():
            raise RuntimeError("Upstash Redis did not answer PING")
        engine = None
        SessionLocal = lambda: store
    else:
        engine = make_engine(settings.DATABASE_URL)
        init_db(engine)
        SessionLocal = session_factory(engine)
    registry = Registry(settings)
    limiter = LoginLimiter(settings.LOGIN_MAX_ATTEMPTS, settings.LOGIN_WINDOW_SECONDS)
    templates = Jinja2Templates(directory=str(TEMPLATE_DIR))

    with SessionLocal() as db:
        bootstrap_owner(db, settings)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield

    app = FastAPI(title="OwnerLink", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    app.state.registry = registry
    app.state.engine = engine
    app.state.storage_backend = "redis" if settings.uses_redis else "sqlite"
    app.state.SessionLocal = SessionLocal

    app.add_middleware(
        SessionMiddleware,
        secret_key=settings.OWNERLINK_SECRET_KEY,
        session_cookie="ownerlink_session",
        max_age=settings.SESSION_MAX_AGE,
        same_site="lax",
        https_only=settings.cookie_secure,
    )

    if STATIC_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

    def db_session() -> Session:
        return SessionLocal()

    def ctx(request: Request, extra: dict | None = None) -> dict:
        data = {"request": request, "csrf_token": ensure_csrf(request), "owner_name": None}
        owner = None
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is not None:
            data["owner_name"] = owner.username
        if extra:
            data.update(extra)
        return data

    def page(request: Request, name: str, extra: dict | None = None, status_code: int = 200):
        return templates.TemplateResponse(request, name, ctx(request, extra), status_code=status_code)

    def device_public(device, live: bool | None = None) -> dict[str, Any]:
        link = registry.get(device.device_id)
        online = registry.is_online(device.device_id) if live is None else live
        battery = device.battery
        charging = device.charging
        if link is not None:
            battery = link.battery if link.battery is not None else battery
            charging = link.charging if link.charging is not None else charging
        return {
            "device_id": device.device_id,
            "name": device.friendly_name,
            "online": online,
            "last_seen": device_ops.last_seen_iso(device.last_seen),
            "battery": battery,
            "charging": charging,
        }

    def require_login_page(request: Request) -> Owner | RedirectResponse:
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is None:
            return redirect_login()
        return owner

    def redirect_login() -> RedirectResponse:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    def json_error(message: str, code: str = "ERROR", status_code: int = 400) -> JSONResponse:
        return JSONResponse({"ok": False, "error": message, "code": code}, status_code=status_code)

    def require_api_owner(request: Request) -> Owner | JSONResponse:
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is None:
            return json_error("Authentication required", "UNAUTHENTICATED", 401)
        return owner

    # ----- health -----
    @app.get("/health")
    async def health():
        return {"ok": True, "service": "ownerlink"}

    # ----- pages -----
    @app.get("/login", response_class=HTMLResponse)
    async def login_page(request: Request):
        db = db_session()
        try:
            if current_owner(request, db):
                return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
        finally:
            db.close()
        return page(request, "login.html", {"error": None})

    @app.post("/login")
    async def login_submit(
        request: Request,
        username: str = Form(""),
        password: str = Form(""),
        csrf_token: str = Form(""),
    ):
        parse_csrf_from_form(csrf_token, request)
        ip = client_ip(request)
        key = f"{ip}:{username.strip().lower()}"
        if limiter.too_many(key) or limiter.too_many(ip):
            return page(request, "login.html", {"error": "Too many attempts. Try again later."}, 429)
        db = db_session()
        try:
            owner = authenticate(db, username.strip(), password)
            if owner is None:
                limiter.record(key)
                limiter.record(ip)
                return page(request, "login.html", {"error": "Invalid username or password"}, 401)
            limiter.clear(key)
            limiter.clear(ip)
            login_owner(request, owner)
            log.info("owner login success")
            return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
        finally:
            db.close()

    @app.post("/logout")
    async def logout(request: Request, csrf_token: str = Form("")):
        parse_csrf_from_form(csrf_token, request)
        logout_owner(request)
        return RedirectResponse("/login", status_code=status.HTTP_303_SEE_OTHER)

    @app.get("/", response_class=HTMLResponse)
    async def dashboard(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            rows = device_ops.list_active_devices(db)
            cards = [device_public(d) for d in rows]
        finally:
            db.close()
        return page(request, "dashboard.html", {"devices": cards})

    @app.get("/devices/add", response_class=HTMLResponse)
    async def add_device_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        return page(request, "add_device.html", {"base_url": settings.OWNERLINK_BASE_URL})

    @app.get("/settings", response_class=HTMLResponse)
    async def settings_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        return page(request, "settings.html", {"message": None, "error": None})

    @app.post("/settings/password")
    async def change_password(
        request: Request,
        current_password: str = Form(""),
        new_password: str = Form(""),
        confirm_password: str = Form(""),
        csrf_token: str = Form(""),
    ):
        parse_csrf_from_form(csrf_token, request)
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            owner = db.get_owner() if isinstance(db, RedisStore) else db.get(Owner, maybe.id)
            error = None
            message = None
            if owner is None or not verify_password(current_password, owner.password_hash):
                error = "Current password is incorrect."
            elif new_password != confirm_password:
                error = "New passwords do not match."
            elif len(new_password) < 12:
                error = "New password must be at least 12 characters."
            else:
                owner.password_hash = hash_password(new_password)
                if isinstance(db, RedisStore):
                    db.save_owner(owner)
                else:
                    db.commit()
                message = "Password updated."
            return page(request, "settings.html", {"message": message, "error": error}, 400 if error else 200)
        finally:
            db.close()

    @app.get("/device/{device_id}", response_class=HTMLResponse)
    async def device_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.get_device(db, device_id)
            if device is None or device.revoked or not device.pairing_consumed:
                return page(request, "error.html", {"message": "Device not found."}, 404)
            card = device_public(device)
        finally:
            db.close()
        return page(request, "device.html", {"device": card})

    @app.get("/device/{device_id}/gallery", response_class=HTMLResponse)
    async def gallery_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "gallery.html", {"device": card})

    @app.get("/device/{device_id}/files", response_class=HTMLResponse)
    async def files_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "files.html", {"device": card})

    @app.get("/device/{device_id}/screen", response_class=HTMLResponse)
    async def screen_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "screen.html", {"device": card})

    # ----- JSON API -----
    @app.get("/api/devices")
    async def api_devices(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            rows = device_ops.list_active_devices(db)
            return {"ok": True, "devices": [device_public(d) for d in rows]}
        finally:
            db.close()

    @app.get("/api/devices/{device_id}")
    async def api_device(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            data = device_public(device)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        if registry.is_online(device_id):
            try:
                status_payload = await registry.command(device_id, P.GET_STATUS, {})
                data["device_status"] = status_payload.get("data") or status_payload
            except (DeviceOffline, CommandError):
                pass
        return {"ok": True, "device": data}

    @app.post("/api/devices/pair")
    async def api_pair(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        body = await _safe_json(request)
        try:
            parse_csrf_from_form((body or {}).get("csrf_token"), request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        if not body:
            return json_error("Expected JSON pairing payload", "BAD_REQUEST")
        payload = body.get("payload") if isinstance(body.get("payload"), dict) else body
        try:
            v = int(payload.get("v") or 0)
        except (TypeError, ValueError):
            v = 0
        device_id = str(payload.get("device_id") or "").strip()
        token = str(payload.get("pairing_token") or "").strip()
        server = str(payload.get("server") or "").strip()
        name = str(body.get("name") or payload.get("name") or "").strip()
        if v != 1:
            return json_error("Unsupported pairing payload version", "BAD_VERSION")
        if not is_uuid(device_id):
            return json_error("Invalid device id", "INVALID_DEVICE")
        if not pairing_token_entropy_ok(token):
            return json_error("Pairing token is invalid", "INVALID_TOKEN")

        connected = registry.get(device_id) is not None
        if not connected:
            for _ in range(20):
                await asyncio.sleep(0.5)
                connected = registry.get(device_id) is not None
                if connected:
                    break
        db = db_session()
        try:
            device, session_token = device_ops.pair_device(
                db,
                device_id=device_id,
                pairing_token=token,
                expected_base_url=settings.OWNERLINK_BASE_URL,
                qr_server=server,
                friendly_name=name or None,
                device_currently_connected=connected,
            )
            link = registry.get(device_id)
            if link is not None:
                link.paired = True
                try:
                    await registry.send_json(
                        device_id,
                        P.envelope(P.PAIR_SUCCESS, device_id, {"session_token": session_token}),
                    )
                except DeviceOffline:
                    return json_error("Device disconnected during pairing", "DEVICE_OFFLINE", 409)
            return {"ok": True, "device": device_public(device)}
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 400)
        finally:
            db.close()

    @app.post("/api/devices/{device_id}/remove")
    async def api_remove(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        try:
            require_csrf(request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        db = db_session()
        try:
            device_ops.revoke_device(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        link = registry.get(device_id)
        if link is not None:
            try:
                await registry.send_json(device_id, P.envelope(P.REVOKED, device_id, {}))
            except Exception:
                pass
            try:
                await link.websocket.close(code=4001, reason="revoked")
            except Exception:
                pass
            await registry.detach(device_id, link.websocket)
        log.info("device removed from dashboard")
        return {"ok": True}

    @app.post("/api/devices/{device_id}/rename")
    async def api_rename(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        try:
            require_csrf(request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        body = await _safe_json(request)
        name = str((body or {}).get("name") or "").strip()
        if not name:
            return json_error("Name is required", "BAD_REQUEST")
        db = db_session()
        try:
            device = device_ops.rename_device(db, device_id, name)
            return {"ok": True, "device": device_public(device)}
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()

    @app.get("/api/devices/{device_id}/gallery")
    async def api_gallery(request: Request, device_id: str, cursor: str | None = None, limit: int = 60):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(
                device_id,
                P.LIST_GALLERY,
                {"cursor": cursor, "limit": max(1, min(limit, 100))},
            )
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)

    @app.get("/api/devices/{device_id}/files")
    async def api_files(request: Request, device_id: str, folder: str | None = None, cursor: str | None = None):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(
                device_id,
                P.LIST_FILES,
                {"folder_id": folder, "cursor": cursor},
            )
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)

    @app.get("/api/devices/{device_id}/thumb")
    async def api_thumb(request: Request, device_id: str, file: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        request_id = str(uuid.uuid4())
        try:
            transfer = await registry.open_thumb(device_id, request_id)
            await registry.send_json(
                device_id,
                P.envelope(P.GET_THUMBNAIL, device_id, {"file_id": file}, request_id),
            )
            chunk = await _wait_bytes(transfer, timeout=settings.COMMAND_TIMEOUT_SECONDS)
            if chunk is None:
                return json_error("Thumbnail unavailable", "NOT_FOUND", 404)
            return StreamingResponse(iter([chunk]), media_type="image/jpeg")
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)
        finally:
            registry.close_transfer(device_id, request_id)

    @app.get("/api/devices/{device_id}/download")
    async def api_download(request: Request, device_id: str, file: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        transfer_id = str(uuid.uuid4())
        try:
            transfer = await registry.open_transfer(device_id, transfer_id)
            await registry.send_json(
                device_id,
                P.envelope(
                    P.DOWNLOAD_FILE,
                    device_id,
                    {"file_id": file, "transfer_id": transfer_id},
                    transfer_id,
                ),
            )
            first = await _wait_queue_item(transfer, timeout=settings.COMMAND_TIMEOUT_SECONDS)
            meta: dict[str, Any] = {}
            first_bytes: bytes | None = None
            if isinstance(first, dict) and "_meta" in first:
                meta = first["_meta"] or {}
            elif isinstance(first, (bytes, bytearray)):
                first_bytes = bytes(first)
            elif first is None:
                return json_error("Empty file", "NOT_FOUND", 404)

            async def generate():
                try:
                    if first_bytes:
                        yield first_bytes
                    while True:
                        item = await _wait_queue_item(transfer, timeout=settings.DOWNLOAD_TIMEOUT_SECONDS)
                        if item is None:
                            break
                        if isinstance(item, dict):
                            continue
                        if isinstance(item, (bytes, bytearray)):
                            yield bytes(item)
                finally:
                    registry.close_transfer(device_id, transfer_id)

            headers = {"Content-Disposition": content_disposition(str(meta.get("name") or "download"))}
            size = meta.get("size")
            if isinstance(size, int) and size >= 0:
                headers["Content-Length"] = str(size)
            mime = str(meta.get("mime") or "application/octet-stream")
            return StreamingResponse(generate(), media_type=mime, headers=headers)
        except DeviceOffline:
            registry.close_transfer(device_id, transfer_id)
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            registry.close_transfer(device_id, transfer_id)
            return json_error(exc.message, exc.code, 400)

    @app.post("/api/devices/{device_id}/gesture")
    async def api_gesture(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        try:
            require_csrf(request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        body = await _safe_json(request) or {}
        try:
            result = await registry.command(device_id, P.REMOTE_GESTURE, body)
            return {"ok": True, **(result.get("data") or {})}
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)

    # ----- websockets -----
    @app.websocket("/ws/device")
    async def device_ws(websocket: WebSocket):
        await websocket.accept()
        state: dict[str, Any] = {"link": None, "device_id": None}
        try:
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    break
                if message.get("text") is not None:
                    try:
                        data = json.loads(message["text"])
                    except json.JSONDecodeError:
                        await websocket.send_json(P.envelope(P.ERROR, "", {"code": "BAD_JSON", "message": "Invalid JSON"}))
                        continue
                    await _handle_device_text(websocket, data, state, SessionLocal, registry)
                elif message.get("bytes") is not None:
                    link = state.get("link")
                    if link is not None:
                        registry.handle_binary(link, message["bytes"])
        except WebSocketDisconnect:
            pass
        except Exception:
            log.exception("device websocket error")
        finally:
            device_id = state.get("device_id")
            if device_id:
                await registry.detach(device_id, websocket)

    @app.websocket("/ws/screen/{device_id}")
    async def screen_ws(websocket: WebSocket, device_id: str):
        await websocket.accept()
        session = websocket.scope.get("session") or {}
        if not session.get("owner_id"):
            await websocket.close(code=4401, reason="Authentication required")
            return
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError:
            db.close()
            await websocket.close(code=4404, reason="Device not found")
            return
        finally:
            db.close()

        if not registry.is_online(device_id):
            await websocket.send_json({"type": "ERROR", "payload": {"code": "DEVICE_OFFLINE", "message": "Device offline"}})
            await websocket.close(code=4409)
            return

        queue = registry.subscribe_screen(device_id)
        first = registry.screen_subscriber_count(device_id) == 1
        try:
            if first:
                await registry.send_json(
                    device_id,
                    P.envelope(P.START_SCREEN_STREAM, device_id, {"fps": 1, "quality": 55, "max_width": 720}),
                )
            await websocket.send_json({"type": "STATUS", "payload": {"online": True, "streaming": True}})
            while True:
                recv_task = _ws_receive_task(websocket)
                get_task = _queue_get_task(queue)
                done, pending = await _wait_first(recv_task, get_task)
                for task in pending:
                    task.cancel()
                if recv_task in done:
                    incoming = recv_task.result()
                    if incoming is None:
                        break
                    await _handle_browser_screen_msg(device_id, incoming, registry)
                if get_task in done:
                    item = get_task.result()
                    if item is None:
                        continue
                    if item[0] == "json":
                        await websocket.send_json(item[1])
                    elif item[0] == "frame":
                        _header, jpeg = item[1], item[2]
                        await websocket.send_json({"type": "FRAME_META", "payload": _header})
                        await websocket.send_bytes(jpeg)
        except WebSocketDisconnect:
            pass
        except DeviceOffline:
            try:
                await websocket.send_json(
                    {"type": "ERROR", "payload": {"code": "DEVICE_OFFLINE", "message": "Device offline"}}
                )
            except Exception:
                pass
        except Exception:
            log.exception("screen websocket error")
        finally:
            remaining = registry.unsubscribe_screen(device_id, queue)
            if remaining == 0:
                try:
                    await registry.send_json(device_id, P.envelope(P.STOP_SCREEN_STREAM, device_id, {}))
                except Exception:
                    pass
            if websocket.client_state == WebSocketState.CONNECTED:
                try:
                    await websocket.close()
                except Exception:
                    pass

    return app


async def _safe_json(request: Request) -> dict | None:
    try:
        body = await request.json()
    except Exception:
        return None
    return body if isinstance(body, dict) else None


async def _handle_device_text(websocket: WebSocket, data: dict, state: dict, SessionLocal, registry: Registry) -> None:
    msg_type = data.get("type")
    device_id = str(data.get("device_id") or "")
    payload = data.get("payload") or {}

    if msg_type == P.UNPAIRED_HELLO:
        token = str(payload.get("pairing_token") or "")
        name = str(payload.get("name") or "Android Device")
        db = SessionLocal()
        try:
            device = device_ops.upsert_unpaired(db, device_id, token, name)
            link = await registry.attach(device.device_id, websocket, paired=False)
            state["link"] = link
            state["device_id"] = device.device_id
            await websocket.send_json(
                P.envelope("UNPAIRED_ACK", device.device_id, {"status": "waiting_for_owner"})
            )
        except device_ops.PairingError as exc:
            await websocket.send_json(
                P.envelope(P.ERROR, device_id, {"code": exc.code, "message": exc.message})
            )
        finally:
            db.close()
        return

    if msg_type == P.DEVICE_AUTH:
        token = str(payload.get("session_token") or "")
        db = SessionLocal()
        try:
            device = device_ops.authenticate_session(db, device_id, token)
            link = await registry.attach(device.device_id, websocket, paired=True)
            state["link"] = link
            state["device_id"] = device.device_id
            await websocket.send_json(P.envelope(P.AUTH_OK, device.device_id, {"name": device.friendly_name}))
            log.info("device authenticated")
        except device_ops.PairingError as exc:
            await websocket.send_json(
                P.envelope(P.ERROR, device_id, {"code": exc.code, "message": exc.message})
            )
        finally:
            db.close()
        return

    link = state.get("link")
    if link is None:
        await websocket.send_json(
            P.envelope(P.ERROR, device_id, {"code": "NOT_AUTHENTICATED", "message": "Send DEVICE_AUTH or UNPAIRED_HELLO first"})
        )
        return

    if msg_type == P.HEARTBEAT:
        link.touch()
        if payload.get("battery") is not None:
            try:
                link.battery = int(payload.get("battery"))
            except (TypeError, ValueError):
                pass
        if payload.get("charging") is not None:
            link.charging = bool(payload.get("charging"))
        db = SessionLocal()
        try:
            device_ops.touch_device(db, link.device_id, link.battery, link.charging)
        finally:
            db.close()
        return

    registry.handle_text(link, data)


async def _wait_queue_item(transfer, timeout: float):
    import asyncio

    item = await asyncio.wait_for(transfer.queue.get(), timeout)
    if isinstance(item, CommandError):
        raise item
    return item


async def _wait_bytes(transfer, timeout: float) -> bytes | None:
    while True:
        item = await _wait_queue_item(transfer, timeout)
        if item is None:
            return None
        if isinstance(item, (bytes, bytearray)):
            return bytes(item)
        if isinstance(item, dict):
            continue


def _ws_receive_task(websocket: WebSocket):
    import asyncio

    async def _inner():
        try:
            message = await websocket.receive()
        except WebSocketDisconnect:
            return None
        if message["type"] == "websocket.disconnect":
            return None
        if message.get("text") is not None:
            try:
                return json.loads(message["text"])
            except json.JSONDecodeError:
                return None
        return None

    return asyncio.create_task(_inner())


def _queue_get_task(queue):
    import asyncio
    return asyncio.create_task(queue.get())


async def _wait_first(*tasks):
    import asyncio
    done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    return done, pending


async def _handle_browser_screen_msg(device_id: str, incoming: dict, registry: Registry) -> None:
    if not isinstance(incoming, dict):
        return
    msg_type = incoming.get("type")
    payload = incoming.get("payload") or incoming
    if msg_type in {"GESTURE", P.REMOTE_GESTURE}:
        try:
            await registry.command(device_id, P.REMOTE_GESTURE, payload)
        except (DeviceOffline, CommandError):
            pass
    elif msg_type == "REQUEST_FRAME":
        try:
            await registry.send_json(
                device_id,
                P.envelope(P.GET_SCREEN_FRAME, device_id, {"quality": 55, "max_width": 720}),
            )
        except DeviceOffline:
            pass


# Use: uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
