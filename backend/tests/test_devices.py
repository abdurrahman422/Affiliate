from tests.conftest import login, new_device_id, pairing_token, unpaired_hello


def _pair(client, csrf, device_id, token, name):
    return client.post(
        "/api/devices/pair",
        json={
            "v": 1,
            "server": "http://testserver",
            "device_id": device_id,
            "pairing_token": token,
            "name": name,
        },
        headers={"X-CSRF-Token": csrf},
    )


def test_two_devices_are_isolated(client):
    csrf = login(client)
    home_id = new_device_id()
    biz_id = new_device_id()
    home_token = pairing_token()
    biz_token = pairing_token()

    with client.websocket_connect("/ws/device") as home_ws:
        unpaired_hello(home_ws, home_id, home_token, "Home Phone")
        assert _pair(client, csrf, home_id, home_token, "Home Phone").status_code == 200
        home_ws.receive_json()
        with client.websocket_connect("/ws/device") as biz_ws:
            unpaired_hello(biz_ws, biz_id, biz_token, "Business Phone")
            assert _pair(client, csrf, biz_id, biz_token, "Business Phone").status_code == 200
            biz_ws.receive_json()

            listing = client.get("/api/devices").json()["devices"]
            names = sorted(d["name"] for d in listing)
            assert names == ["Business Phone", "Home Phone"]
            ids = {d["device_id"] for d in listing}
            assert ids == {home_id, biz_id}

            home_page = client.get(f"/device/{home_id}")
            assert home_page.status_code == 200
            assert "Home Phone" in home_page.text
            assert "Business Phone" not in home_page.text

            biz_page = client.get(f"/device/{biz_id}")
            assert biz_page.status_code == 200
            assert "Business Phone" in biz_page.text
            assert "Home Phone" not in biz_page.text

            home_api = client.get(f"/api/devices/{home_id}").json()["device"]
            biz_api = client.get(f"/api/devices/{biz_id}").json()["device"]
            assert home_api["device_id"] == home_id
            assert biz_api["device_id"] == biz_id
            assert home_api["name"] != biz_api["name"]

            removed = client.post(
                f"/api/devices/{home_id}/remove",
                json={},
                headers={"X-CSRF-Token": csrf},
            )
            assert removed.status_code == 200

            remaining = client.get("/api/devices").json()["devices"]
            assert [d["name"] for d in remaining] == ["Business Phone"]
            assert remaining[0]["device_id"] == biz_id

            missing = client.get(f"/device/{home_id}")
            assert missing.status_code == 404
            still = client.get(f"/device/{biz_id}")
            assert still.status_code == 200


def test_rename_device(client):
    csrf = login(client)
    device_id = new_device_id()
    token = pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "Phone")
        assert _pair(client, csrf, device_id, token, "Phone").status_code == 200
        ws.receive_json()
    response = client.post(
        f"/api/devices/{device_id}/rename",
        json={"name": "Office Pixel"},
        headers={"X-CSRF-Token": csrf},
    )
    assert response.status_code == 200
    assert response.json()["device"]["name"] == "Office Pixel"
