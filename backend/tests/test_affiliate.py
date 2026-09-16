from __future__ import annotations

import uuid

import pytest

from app.affiliate import AffiliateError, AffiliateService, eligible, validate_field_schema, validate_submission
from app.models import AffiliateAuditEvent, AffiliateUser
from app.storage import RedisStore
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


def test_review_video_fields_require_meaningful_multilingual_answers():
    schema = [
        {"id": "improve_review", "type": "long_text", "label": "What should improve?", "required": True, "min_length": 60, "min_words": 12},
        {"id": "liked_review", "type": "long_text", "label": "What did you like?", "required": True, "min_length": 60, "min_words": 12},
        {"id": "review_screenshot", "type": "image", "label": "Screenshot", "required": True, "allowed_types": ["image/png"]},
    ]
    good = "The opening scene was clear and the explanation helped me understand every important point in this video."
    screenshot = {"name": "review.png", "mime": "image/png", "size": 1234, "sha256": "a" * 64, "file_id": "cHJvb2Y"}
    values, proofs = validate_submission(schema, {"improve_review": good, "liked_review": good, "review_screenshot": screenshot})
    assert len(proofs) == 1 and values["review_screenshot"]["name"] == "review.png"
    with pytest.raises(AffiliateError):
        validate_submission(schema, {"improve_review": "good good good " * 12, "liked_review": good, "review_screenshot": screenshot})
    with pytest.raises(AffiliateError):
        validate_submission(schema, {"improve_review": good, "liked_review": "Too short", "review_screenshot": screenshot})
    with pytest.raises(AffiliateError):
        validate_submission(schema, {"improve_review": good, "liked_review": good})


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
    assert "Create task" in client.get("/tasks").text
    assert client.get("/categories").status_code == 200
    assert client.get("/task-import").status_code == 200
    assert "Coin Ledger" in client.get("/coin-ledger").text
    assert "Devices" in client.get("/devices").text


def test_json_import_creates_form_fields_and_dashboard_displays_answers(client, app):
    csrf = login(client)
    payload = {
        "version": 1,
        "category": {"name": "Student Survey", "description": "Voluntary student survey"},
        "task": {
            "title": "Student information survey", "task_type": "FORM",
            "description": "Three survey questions", "instructions": "Answer every question.",
            "reward": "0.5", "repeat_limit": 1, "publish": True,
            "response_fields": [
                {"id": "full_name", "type": "short_text", "label": "Your name", "required": True},
                {"id": "age", "type": "number", "label": "Your age", "required": True, "min": 5, "max": 120},
                {"id": "current_class", "type": "short_text", "label": "Which class do you study in?", "required": True},
            ],
        },
    }
    imported = admin_post(client, "/api/tasks/import", payload, csrf)
    assert imported.status_code == 200
    task = imported.json()["task"]
    assert task["status"] == "PUBLISHED" and task["task_type"] == "FORM"
    assert [field["label"] for field in task["field_schema"]] == ["Your name", "Your age", "Which class do you study in?"]

    with app.state.SessionLocal() as db:
        submission = AffiliateService(db).submit(task["task_id"], uuid.uuid4().hex, "survey-user", "survey-phone", {
            "full_name": "Test User", "age": 18, "current_class": "Class 12",
        })
        assert submission["submitted_fields"]["current_class"] == "Class 12"
    rendered = client.get("/submissions")
    assert rendered.status_code == 200
    assert "Which class do you study in?" in rendered.text and "Class 12" in rendered.text


def test_json_import_creates_url_task_without_duration_or_priority(client):
    csrf = login(client)
    imported = admin_post(client, "/api/tasks/import", {
        "version": 1,
        "category": {"name": "Video Watch", "description": "Public video tasks"},
        "task": {
            "title": "Watch the public video", "task_type": "URL",
            "action_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "description": "Watch the assigned video", "instructions": "Open, watch, and confirm.",
            "reward": "0.5", "publish": True, "duration": 500, "priority": 99,
        },
    }, csrf)
    assert imported.status_code == 200
    task = imported.json()["task"]
    assert task["task_type"] == "URL" and task["action_url"].startswith("https://www.youtube.com/")
    assert task["action_duration_seconds"] == 0 and task["priority"] == 0


def test_sequential_json_import_reveals_only_next_url_and_rejects_locked_submit(client, app):
    csrf = login(client)
    urls = [f"https://www.youtube.com/watch?v=private_video_{index}" for index in range(1, 4)]
    imported = admin_post(client, "/api/tasks/import", {
        "version": 1,
        "category": {"name": "Private Video Sequence", "description": "One video at a time"},
        "series": {"name": "Watch YouTube Video"},
        "task_defaults": {
            "task_type": "URL", "title_prefix": "Watch YouTube Video",
            "description": "Watch the assigned video", "instructions": "Open, watch, confirm, and submit.",
            "reward": "0.5", "publish": True,
        },
        "tasks": [{"action_url": url} for url in urls],
    }, csrf)
    assert imported.status_code == 200
    tasks = imported.json()["tasks"]
    assert len(tasks) == 3 and len({task["sequence_id"] for task in tasks}) == 1

    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        first_sync = service.sync("sequence-user", "sequence-phone", "Sequence User", 0)
        sequence_tasks = [task for task in first_sync["tasks"] if task.get("sequence_id") == tasks[0]["sequence_id"]]
        assert [task["action_url"] for task in sequence_tasks] == [urls[0]]
        assert urls[1] not in str(first_sync) and urls[2] not in str(first_sync)
        with pytest.raises(AffiliateError) as locked:
            service.submit(tasks[2]["task_id"], uuid.uuid4().hex, "sequence-user", "sequence-phone", {})
        assert locked.value.code == "TASK_LOCKED"

        service.submit(tasks[0]["task_id"], uuid.uuid4().hex, "sequence-user", "sequence-phone", {})
        second_sync = service.sync("sequence-user", "sequence-phone", "Sequence User", 0)
        sequence_tasks = [task for task in second_sync["tasks"] if task.get("sequence_id") == tasks[0]["sequence_id"]]
        assert [task["action_url"] for task in sequence_tasks] == [urls[1]]
        assert urls[0] not in str(second_sync) and urls[2] not in str(second_sync)


def test_redis_sequence_import_uses_one_bulk_pipeline():
    db = RedisStore.__new__(RedisStore)
    pipeline_calls = []
    db._key = lambda suffix: f"ownerlink:v1:{suffix}"
    db._cmd = lambda *parts: 3 if parts[0] == "INCRBY" else None
    db._pipeline = lambda commands: pipeline_calls.append(commands) or ["OK"] * len(commands)
    service = AffiliateService(db)
    service.categories = lambda: [{"category_id": "watch", "name": "Watch", "enabled": True}]
    rows = [{
        "category_id": "watch", "title": f"Watch Video {index}", "task_type": "URL",
        "action_url": f"https://www.youtube.com/watch?v=video_{index}",
        "description": "Watch the assigned video", "instructions": "Watch and submit",
        "reward": "0.5", "sequence_id": "sequence_test", "sequence_order": index,
    } for index in range(1, 4)]

    created = service.import_published_sequence(rows, "owner")

    assert len(created) == 3
    assert [task["revision"] for task in created] == [1, 2, 3]
    assert all(task["status"] == "PUBLISHED" for task in created)
    assert len(pipeline_calls) == 1
    assert len(pipeline_calls[0]) == 12


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
    assert eligible({"eligibility": {"type": "all", "gender": "FEMALE"}}, "u", "d", 100, set(), "FEMALE")
    assert not eligible({"eligibility": {"type": "all", "gender": "FEMALE"}}, "u", "d", 100, set(), "MALE")


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


def test_sync_reports_dashboard_reward_as_account_history(app):
    with app.state.SessionLocal() as db:
        service = AffiliateService(db)
        service.manual_adjust("reward-user", "50", "Dashboard connection bonus", "system",
                              "dashboard-connection-bonus:reward-user", "DASHBOARD_BONUS")
        sync = service.sync("reward-user", "reward-device", "Reward User", 0)
        assert sync["wallet"]["balance"] == "50"
        assert sync["wallet"]["dashboard_reward_claimed"] is True


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
