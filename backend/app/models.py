from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def utcnow() -> datetime:
    return datetime.utcnow()


class Base(DeclarativeBase):
    pass


class Owner(Base):
    __tablename__ = "owner"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    username: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    friendly_name: Mapped[str] = mapped_column(String(128), nullable=False, default="Android Device")
    pairing_token_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    pairing_consumed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    device_session_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    last_seen: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    battery: Mapped[int | None] = mapped_column(Integer, nullable=True)
    charging: Mapped[bool | None] = mapped_column(Boolean, nullable=True)


class AffiliateUser(Base):
    __tablename__ = "affiliate_users"

    user_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    display_name: Mapped[str] = mapped_column(String(128), nullable=False)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True, default="")
    phone_number: Mapped[str | None] = mapped_column(String(20), unique=True, nullable=True, index=True)
    password_hash: Mapped[str | None] = mapped_column(Text, nullable=True)
    age: Mapped[int | None] = mapped_column(Integer, nullable=True)
    education: Mapped[str | None] = mapped_column(String(80), nullable=True)
    address: Mapped[str | None] = mapped_column(Text, nullable=True)
    site_address: Mapped[str | None] = mapped_column(Text, nullable=True)
    withdrawal_method: Mapped[str | None] = mapped_column(String(16), nullable=True)
    withdrawal_number: Mapped[str | None] = mapped_column(String(20), nullable=True)
    profile_photo_data: Mapped[str | None] = mapped_column(Text, nullable=True)
    profile_photo_mime: Mapped[str | None] = mapped_column(String(64), nullable=True)
    registered: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="ACTIVE")
    balance_milli: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    quality_score: Mapped[int] = mapped_column(Integer, nullable=False, default=100)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateSession(Base):
    __tablename__ = "affiliate_sessions"

    session_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    revoked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateCampaign(Base):
    __tablename__ = "affiliate_campaigns"

    campaign_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="DRAFT", index=True)
    start_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    end_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    eligibility_json: Mapped[str] = mapped_column(Text, nullable=False, default='{"type":"all"}')
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")
    created_by: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateCategory(Base):
    __tablename__ = "affiliate_categories"

    category_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(80), nullable=False, unique=True)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    editable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateTaskTemplate(Base):
    __tablename__ = "affiliate_task_templates"

    template_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    category_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    field_schema_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    default_reward_milli: Mapped[int] = mapped_column(Integer, nullable=False, default=500)
    verification_mode: Mapped[str] = mapped_column(String(32), nullable=False, default="MANUAL")
    editable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateDeviceGroup(Base):
    __tablename__ = "affiliate_device_groups"

    group_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(120), nullable=False, unique=True)
    device_ids_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    created_by: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateTask(Base):
    __tablename__ = "affiliate_tasks"

    task_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    campaign_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    template_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    category_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    instructions: Mapped[str] = mapped_column(Text, nullable=False, default="")
    task_type: Mapped[str] = mapped_column(String(16), nullable=False, default="FORM")
    action_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    action_duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    sequence_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    sequence_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    reward_milli: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="DRAFT", index=True)
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    start_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    end_at: Mapped[str | None] = mapped_column(String(40), nullable=True)
    eligibility_json: Mapped[str] = mapped_column(Text, nullable=False, default='{"type":"all"}')
    repeat_limit: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    max_submissions: Mapped[int | None] = mapped_column(Integer, nullable=True)
    field_schema_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    verification_mode: Mapped[str] = mapped_column(String(32), nullable=False, default="MANUAL")
    created_by: Mapped[str] = mapped_column(String(128), nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateSubmission(Base):
    __tablename__ = "affiliate_submissions"
    __table_args__ = (
        UniqueConstraint("client_submission_id", name="uq_affiliate_client_submission"),
        UniqueConstraint("task_id", "user_id", "device_id", "attempt", name="uq_affiliate_task_attempt"),
    )

    submission_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    client_submission_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    task_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    attempt: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="PENDING_REVIEW", index=True)
    submitted_fields_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    proof_metadata_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    reviewed_by: Mapped[str | None] = mapped_column(String(128), nullable=True)
    review_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateCoinLedger(Base):
    __tablename__ = "affiliate_coin_ledger"

    ledger_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    submission_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    task_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    amount_milli: Mapped[int] = mapped_column(Integer, nullable=False)
    entry_type: Mapped[str] = mapped_column(String(32), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    actor: Mapped[str] = mapped_column(String(128), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False, unique=True)
    balance_after_milli: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class AffiliateAuditEvent(Base):
    __tablename__ = "affiliate_audit_log"

    event_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    action: Mapped[str] = mapped_column(String(80), nullable=False, index=True)
    entity_type: Mapped[str] = mapped_column(String(48), nullable=False)
    entity_id: Mapped[str] = mapped_column(String(128), nullable=False)
    actor: Mapped[str] = mapped_column(String(128), nullable=False)
    detail_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
