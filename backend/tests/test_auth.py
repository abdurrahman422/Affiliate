from tests.conftest import csrf_token, login


def test_root_redirects_when_logged_out(client):
    response = client.get("/", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"


def test_wrong_password_stays_on_login(client):
    token = csrf_token(client, "/login")
    response = client.post(
        "/login",
        data={"username": "owner", "password": "definitely-wrong-password", "csrf_token": token},
        follow_redirects=False,
    )
    assert response.status_code == 401
    assert "Invalid username or password" in response.text
    dashboard = client.get("/", follow_redirects=False)
    assert dashboard.status_code == 303


def test_correct_login_opens_dashboard(client):
    login(client)
    response = client.get("/")
    assert response.status_code == 200
    assert "Devices" in response.text
    assert "Add Device" in response.text


def test_logout_blocks_dashboard(client):
    token = login(client)
    response = client.post("/logout", data={"csrf_token": token}, follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"
    blocked = client.get("/", follow_redirects=False)
    assert blocked.status_code == 303


def test_api_requires_auth(client):
    response = client.get("/api/devices")
    assert response.status_code == 401
    body = response.json()
    assert body["ok"] is False
