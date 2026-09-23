from tests.conftest import login, new_device_id, pairing_token, unpaired_hello
from app.registry import CommandError, DeviceOffline


def _paired_device(client):
    csrf = login(client)
    device_id, token = new_device_id(), pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Call Phone")
        response = client.post("/api/devices/pair", json={"v": 1, "server": "http://testserver", "device_id": device_id, "pairing_token": token, "name": "Call Phone"}, headers={"X-CSRF-Token": csrf})
        assert response.status_code == 200
        ws.receive_json()
    return device_id


def test_call_logs_require_dashboard_auth(client):
    assert client.get(f"/api/devices/{new_device_id()}/call-logs").status_code == 401


def test_device_page_shows_call_logs_and_remote_screen(client):
    device_id = _paired_device(client)
    page = client.get(f"/device/{device_id}")
    assert page.status_code == 200
    assert "Call Logs" in page.text
    assert "Remote Screen" in page.text
    assert "Remote Workspace" not in page.text
    screen = client.get(f"/device/{device_id}/screen")
    assert screen.status_code == 200
    assert "Low-FPS Accessibility screenshot" in screen.text


def test_remote_screen_page_requires_dashboard_login(client):
    response = client.get(f"/device/{new_device_id()}/screen", follow_redirects=False)
    assert response.status_code in {302, 303, 307}


def test_call_logs_offline_and_filter_validation(client):
    device_id = _paired_device(client)
    bad = client.get(f"/api/devices/{device_id}/call-logs?filter=secret")
    assert bad.status_code == 400
    offline = client.get(f"/api/devices/{device_id}/call-logs")
    assert offline.status_code == 409
    assert offline.json()["error"] == "Device is offline."


def test_call_log_permission_error_is_clean(client, app, monkeypatch):
    device_id = _paired_device(client)
    async def denied(*_args, **_kwargs):
        raise CommandError("CALL_LOG_PERMISSION_REQUIRED", "private provider error")
    monkeypatch.setattr(app.state.registry, "command", denied)
    response = client.get(f"/api/devices/{device_id}/call-logs")
    assert response.status_code == 403
    assert response.json()["error"] == "Call Log permission is required on this device."


def test_call_log_pagination_payload_is_bounded(client, app, monkeypatch):
    device_id = _paired_device(client)
    captured = {}
    async def page(_device_id, command, payload):
        captured.update(payload)
        return {"data": {"items": [], "next_cursor": None}}
    monkeypatch.setattr(app.state.registry, "command", page)
    response = client.get(f"/api/devices/{device_id}/call-logs?cursor=20&limit=500&filter=missed")
    assert response.status_code == 200
    assert response.json()["items"] == []
    assert captured == {"cursor": 20, "limit": 100, "filter": "missed"}
