import base64

from app.models import AffiliateSession, AffiliateUser
from app.affiliate import AffiliateService
from app.affiliate_accounts import AffiliateAccountService


PHOTO = base64.b64encode(b"small-test-image").decode()


def payload(**overrides):
    value = {
        "full_name": "Rahim Worker", "phone_number": "01712345678", "age": 24, "gender": "MALE",
        "education": "Graduate", "address": "Dhaka, Bangladesh", "site_address": "example.com",
        "withdrawal_method": "bKash", "withdrawal_number": "01812345678",
        "password": "Secure123", "confirm_password": "Secure123",
        "profile_photo_base64": PHOTO, "profile_photo_mime": "image/jpeg", "device_id": "phone-a",
    }
    value.update(overrides); return value


def register(client, **overrides): return client.post("/api/auth/register", json=payload(**overrides))


def auth(response): return {"Authorization": "Bearer " + response.json()["token"]}


def test_registration_hashes_password_and_never_returns_secrets(client, app):
    response = register(client)
    assert response.status_code == 200
    body = response.json(); assert body["profile"]["phone_number"] == "+8801712345678"
    assert "password" not in str(body["profile"]).lower()
    with app.state.SessionLocal() as db:
        row = db.get(AffiliateUser, body["user_id"])
        assert row.password_hash and row.password_hash.startswith("$argon2")
        assert row.password_hash != "Secure123"


def test_duplicate_and_registration_validation(client):
    assert register(client).status_code == 200
    assert register(client).status_code == 409
    assert register(client, phone_number="123").json()["code"] == "INVALID_PHONE"
    assert register(client, gender="OTHER").json()["code"] == "INVALID_GENDER"
    assert register(client, withdrawal_method="Rocket").json()["code"] == "INVALID_WITHDRAWAL_METHOD"
    assert register(client, withdrawal_number="123").json()["code"] == "INVALID_PHONE"
    assert register(client, password="weak", confirm_password="weak").json()["code"] == "WEAK_PASSWORD"


def test_login_session_profile_isolation_and_logout(client):
    first = register(client)
    second = register(client, phone_number="01912345678", withdrawal_number="01612345678", full_name="Second User")
    assert client.post("/api/auth/login", json={"phone_number": "01712345678", "password": "wrong"}).status_code == 401
    assert client.post("/api/auth/login", json={"phone_number": "01512345678", "password": "Secure123"}).status_code == 401
    login = client.post("/api/auth/login", json={"phone_number": "01712345678", "password": "Secure123"})
    assert login.status_code == 200
    headers = auth(login)
    assert client.get("/api/auth/session", headers=headers).status_code == 200
    assert client.get("/api/profile").status_code == 401
    updated = client.put("/api/profile", headers=headers, json={"full_name": "Rahim Updated", "age": 25, "gender": "FEMALE"})
    assert updated.json()["profile"]["full_name"] == "Rahim Updated"
    assert updated.json()["profile"]["gender"] == "FEMALE"
    other_id = second.json()["user_id"]
    assert client.get(f"/api/users/{other_id}/photo", headers=headers).status_code == 403
    assert client.post("/api/auth/logout", headers=headers).status_code == 200
    assert client.get("/api/auth/session", headers=headers).status_code == 401


def test_admin_users_page_masks_payout_and_omits_password(client):
    register(client)
    from conftest import login
    login(client)
    page = client.get("/users")
    assert page.status_code == 200 and "Rahim Worker" in page.text and "01******678" in page.text
    assert "$argon2" not in page.text and "Secure123" not in page.text


def test_authenticated_user_owns_submissions_and_wallet(client, app):
    response = register(client)
    token, user_id = response.json()["token"], response.json()["user_id"]
    with app.state.SessionLocal() as db:
        account = AffiliateAccountService(db).authenticate(token)
        assert account["user_id"] == user_id
        service = AffiliateService(db)
        sync = service.sync(user_id, "phone-a", "Rahim Worker", 0)
        task = sync["tasks"][0]
        fields = {"public_url": "https://example.com/public", "review_note": "A complete authorized QA review.", "quality": "Pass"}
        result = service.submit(task["task_id"], "client-auth-123", user_id, "phone-a", fields)
        assert result["user_id"] == user_id
        assert service.submissions(user_id=user_id)[0]["submission_id"] == result["submission_id"]
        assert service.sync(user_id, "phone-a", "Rahim Worker", 0)["wallet"]["balance"] == "0"
