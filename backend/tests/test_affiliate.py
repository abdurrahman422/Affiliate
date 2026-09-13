from __future__ import annotations

import uuid

import pytest

from app.affiliate import AffiliateError, AffiliateService, eligible, validate_field_schema, validate_submission
from app.models import AffiliateAuditEvent, AffiliateUser
from tests.conftest import login, new_device_id, pairing_token, unpaired_hello
from tests.test_chat_routing import connect_identity


def task_payload(**overrides):
    value = {
        "title": "Public page QA",
        "category_id": "facebook",
        "description": "Review public page quality",
        "instructions": "Open the authorized public page and report visible QA findings.",
        "reward": "0.50",
        "repeat_limit": 1,
        "eligibility": {"type": "all"},
        "field_schema": [
            {"id": "public_url", "type": "url", "label": "Public URL", "required": True},
            {"id": "review_note", "type": "long_text", "label": "Review note", "required": True, "min_length": 5},
        ],
    }
    value.update(overrides)
    return value


def admin_post(client, path, body, csrf):
    return client.post(path, json=body, headers={"X-CSRF-Token": csrf})


def test_seed_categories_templates_and_tasks(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        assert {row["name"] for row in service.categories()} == {"Facebook", "Instagram", "YouTube"}
        assert len(service.templates()) == 10
        assert len(service.tasks(status="PUBLISHED")) == 3
        serialized = str(service.templates()).lower()
        for forbidden in ("fake account", "artificial likes", "password", "session token"):
            assert forbidden not in serialized


def test_admin_task_crud_lifecycle_and_dashboard(client):
    csrf = login(client)
    created = admin_post(client, "/api/tasks", task_payload(), csrf)
    assert created.status_code == 200
    task = created.json()["task"]
    assert task["status"] == "DRAFT"
    edited = client.put(f"/api/tasks/{task['task_id']}", json={"title": "Updated public page QA", "reward": "0.75"}, headers={"X-CSRF-Token": csrf})
    assert edited.status_code == 200 and edited.json()["task"]["reward"] == "0.75"
    for state in ("PUBLISHED", "PAUSED", "PUBLISHED", "CLOSED", "ARCHIVED"):
        response = admin_post(client, f"/api/tasks/{task['task_id']}/status", {"status": state}, csrf)
        assert response.status_code == 200
        assert response.json()["task"]["status"] == state
    assert client.get("/tasks").status_code == 200
    assert "Dynamic task builder" in client.get("/tasks").text
    assert "Coin Ledger" in client.get("/coin-ledger").text
    assert "Devices" in client.get("/devices").text


def test_campaign_lifecycle_and_auth(client):
    assert client.get("/api/campaigns").status_code == 401
    csrf = login(client)
    response = admin_post(client, "/api/campaigns", {"name": "September QA", "eligibility": {"type": "all"}}, csrf)
    assert response.status_code == 200
    campaign_id = response.json()["campaign"]["campaign_id"]
    for state in ("PUBLISHED", "PAUSED", "CLOSED", "ARCHIVED"):
        assert admin_post(client, f"/api/campaigns/{campaign_id}/status", {"status": state}, csrf).status_code == 200


def test_dynamic_schema_validation_and_prohibited_fields():
    schema = validate_field_schema(task_payload()["field_schema"])
    values, proofs = validate_submission(schema, {"public_url": "https://example.com/post", "review_note": "Clear and accurate review"})
    assert values["public_url"].startswith("https://")
    assert proofs == []
    with pytest.raises(AffiliateError) as exc:
        validate_field_schema([{"id": "password", "type": "short_text", "label": "Account password"}])
    assert exc.value.code == "PROHIBITED_FIELD"
    with pytest.raises(AffiliateError):
        validate_submission(schema, {"public_url": "javascript:alert(1)", "review_note": "valid review"})
    proof_schema = validate_field_schema([{"id": "proof", "type": "image", "label": "QA screenshot", "required": True, "max_size_mb": 1}])
    _, proofs = validate_submission(proof_schema, {"proof": {"name": "screen.png", "mime": "image/png", "size": 500,
        "sha256": "a" * 64, "file_id": "safe_opaque_id"}})
    assert proofs[0]["file_id"] == "safe_opaque_id"


def test_eligibility_rules():
    assert eligible({"eligibility": {"type": "all"}}, "u", "d")
    assert eligible({"eligibility": {"type": "devices", "ids": ["d"]}}, "u", "d")
    assert not eligible({"eligibility": {"type": "devices", "ids": ["other"]}}, "u", "d")
    assert not eligible({"eligibility": {"type": "all", "minimum_quality_score": 90}}, "u", "d", 80)


def _published(service: AffiliateService, **overrides):
    task = service.create_task(task_payload(**overrides), "owner")
    return service.task_status(task["task_id"], "PUBLISHED", "owner")


def test_duplicate_submission_review_revision_and_idempotent_credit(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        task = _published(service)
        fields = {"public_url": "https://example.com/page", "review_note": "Everything is readable"}
        client_id = uuid.uuid4().hex
        first = service.submit(task["task_id"], client_id, "worker-1", "device-1", fields)
        assert service.submit(task["task_id"], client_id, "worker-1", "device-1", fields)["submission_id"] == first["submission_id"]
        with pytest.raises(AffiliateError) as duplicate:
            service.submit(task["task_id"], uuid.uuid4().hex, "worker-1", "device-1", fields)
        assert duplicate.value.code == "DUPLICATE_SUBMISSION"

        revised, _ = service.review(first["submission_id"], "REVISION_REQUESTED", "Add one more detail", "owner")
        assert revised["status"] == "REVISION_REQUESTED"
        resubmitted = service.submit(task["task_id"], uuid.uuid4().hex, "worker-1", "device-1", {**fields, "review_note": "Everything is readable and captions match"})
        assert resubmitted["submission_id"] == first["submission_id"]
        assert resubmitted["status"] == "PENDING_REVIEW"

        approved, entry = service.review(first["submission_id"], "APPROVED", "Verified", "owner")
        again, duplicate_entry = service.review(first["submission_id"], "APPROVED", "Verified twice", "owner")
        assert approved["status"] == again["status"] == "APPROVED"
        assert entry["ledger_id"] == duplicate_entry["ledger_id"]
        assert len(service.ledger(user_id="worker-1")) == 1
        assert db.get(AffiliateUser, "worker-1").balance_milli == 500


def test_reject_requires_reason_and_manual_adjustment_is_audited(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        task = _published(service)
        fields = {"public_url": "https://example.com", "review_note": "A complete note"}
        submission = service.submit(task["task_id"], uuid.uuid4().hex, "worker-2", "device-2", fields)
        with pytest.raises(AffiliateError): service.review(submission["submission_id"], "REJECTED", "", "owner")
        rejected, _ = service.review(submission["submission_id"], "REJECTED", "Link is not the requested page", "owner")
        assert rejected["status"] == "REJECTED"
        first = service.manual_adjust("worker-2", "1.25", "Quality bonus", "owner", "manual-test-1")
        second = service.manual_adjust("worker-2", "1.25", "Duplicate request", "owner", "manual-test-1")
        assert first["ledger_id"] == second["ledger_id"]
        assert db.query(AffiliateAuditEvent).filter(AffiliateAuditEvent.action == "COIN_ADJUSTED").count() == 1


def test_closed_task_rejects_new_submission(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        task = _published(service)
        service.task_status(task["task_id"], "CLOSED", "owner")
        with pytest.raises(AffiliateError) as exc:
            service.submit(task["task_id"], uuid.uuid4().hex, "worker", "device", {"public_url": "https://example.com", "review_note": "A valid review"})
        assert exc.value.code == "TASK_NOT_OPEN"


def test_task_websocket_sync_and_queued_submission_ack(client):
    device_id = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        contact_id = connect_identity(ws, device_id, "Worker")
        ws.send_json({"v": 1, "type": "TASK_SYNC_REQUEST", "device_id": device_id, "request_id": "sync-1", "payload": {"cursor": 0}})
        sync = ws.receive_json()
        assert sync["type"] == "TASK_SYNC" and len(sync["payload"]["tasks"]) == 3
        task = sync["payload"]["tasks"][0]
        ws.send_json({"v": 1, "type": "TASK_SUBMIT", "device_id": device_id, "request_id": "submit-1", "payload": {
            "task_id": task["task_id"], "client_submission_id": uuid.uuid4().hex,
            "fields": {"public_url": "https://example.com/public", "review_note": "A complete public-content QA review", "quality": "Pass"},
        }})
        ack = ws.receive_json()
        assert ack["type"] == "TASK_SUBMISSION_ACK"
        assert ack["payload"]["submission"]["user_id"] == contact_id
        assert ack["payload"]["submission"]["status"] == "PENDING_REVIEW"


def test_unproven_socket_cannot_sync_tasks(client):
    device_id = new_device_id()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, pairing_token(), "Unknown")
        ws.send_json({"v": 1, "type": "TASK_SYNC_REQUEST", "device_id": device_id, "payload": {}})
        assert ws.receive_json()["payload"]["code"] == "TASK_IDENTITY_REQUIRED"


def test_dynamic_category_template_group_and_group_eligibility(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        category = service.create_category({"name": "Website QA", "description": "Authorized site review"}, "owner")
        template = service.create_template({"name": "Accessibility QA", "category_id": category["category_id"], "default_reward": "0.75",
            "field_schema": [{"id": "note", "type": "long_text", "label": "Findings", "required": True}]}, "owner")
        group = service.create_group({"name": "QA phones", "device_ids": ["device-in"]}, "owner")
        task = _published(service, category_id=category["category_id"], template_id=template["template_id"], eligibility={"type": "groups", "ids": [group["group_id"]]})
        assert any(row["task_id"] == task["task_id"] for row in service.sync("u", "device-in", "Worker")["tasks"])
        assert all(row["task_id"] != task["task_id"] for row in service.sync("v", "device-out", "Other")["tasks"])
