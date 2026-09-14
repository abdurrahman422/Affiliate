from collections.abc import Generator
from pathlib import Path

from sqlalchemy import create_engine, event, inspect, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from .models import Base


def _sqlite_path_from_url(url: str) -> Path | None:
    if not url.startswith("sqlite"):
        return None
    raw = url.split("sqlite:///", 1)[-1]
    if raw.startswith("/") and not raw.startswith("///"):
        return Path(raw)
    if url.startswith("sqlite:////"):
        return Path("/" + url.split("sqlite:////", 1)[-1].lstrip("/"))
    if raw in {":memory:", ""}:
        return None
    return Path(raw)


def make_engine(database_url: str) -> Engine:
    connect_args = {}
    engine_kwargs: dict = {"future": True}
    if database_url.startswith("sqlite"):
        connect_args = {"check_same_thread": False}
        if ":memory:" in database_url:
            engine_kwargs["poolclass"] = StaticPool
        else:
            path = _sqlite_path_from_url(database_url)
            if path is not None and str(path) not in {":memory:"}:
                path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(database_url, connect_args=connect_args, **engine_kwargs)

    if database_url.startswith("sqlite"):

        @event.listens_for(engine, "connect")
        def _set_sqlite_pragma(dbapi_connection, _connection_record):  # type: ignore[no-untyped-def]
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.close()

    return engine


def init_db(engine: Engine) -> None:
    Base.metadata.create_all(bind=engine)
    # Lightweight additive migration for installations created before Affiliate accounts.
    if engine.url.drivername.startswith("sqlite"):
        existing = {column["name"] for column in inspect(engine).get_columns("affiliate_users")}
        additions = {
            "phone_number": "VARCHAR(20)", "password_hash": "TEXT", "age": "INTEGER",
            "education": "VARCHAR(80)", "address": "TEXT", "site_address": "TEXT",
            "withdrawal_method": "VARCHAR(16)", "withdrawal_number": "VARCHAR(20)",
            "profile_photo_data": "TEXT", "profile_photo_mime": "VARCHAR(64)",
            "registered": "BOOLEAN NOT NULL DEFAULT 0",
        }
        with engine.begin() as connection:
            for name, declaration in additions.items():
                if name not in existing:
                    connection.execute(text(f"ALTER TABLE affiliate_users ADD COLUMN {name} {declaration}"))
            connection.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS ix_affiliate_users_phone_number ON affiliate_users(phone_number)"))
        category_columns = {column["name"] for column in inspect(engine).get_columns("affiliate_categories")}
        if "enabled" not in category_columns:
            with engine.begin() as connection:
                connection.execute(text("ALTER TABLE affiliate_categories ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT 1"))
        task_columns = {column["name"] for column in inspect(engine).get_columns("affiliate_tasks")}
        task_additions = {
            "task_type": "VARCHAR(16) NOT NULL DEFAULT 'FORM'",
            "action_url": "TEXT",
            "action_duration_seconds": "INTEGER NOT NULL DEFAULT 0",
        }
        with engine.begin() as connection:
            for name, declaration in task_additions.items():
                if name not in task_columns:
                    connection.execute(text(f"ALTER TABLE affiliate_tasks ADD COLUMN {name} {declaration}"))


def session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


def get_db_from(factory: sessionmaker[Session]) -> Generator[Session, None, None]:
    db = factory()
    try:
        yield db
    finally:
        db.close()
