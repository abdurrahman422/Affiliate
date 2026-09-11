from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


_BACKEND_DIR = Path(__file__).resolve().parent.parent
_ROOT_DIR = _BACKEND_DIR.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(
            str(_BACKEND_DIR / ".env"),
            str(_ROOT_DIR / ".env"),
        ),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    OWNERLINK_ADMIN_USERNAME: str = ""
    OWNERLINK_ADMIN_PASSWORD: str = ""
    OWNERLINK_SECRET_KEY: str = ""
    OWNERLINK_BASE_URL: str = "http://127.0.0.1:8000"
    DATABASE_URL: str = "sqlite:///./ownerlink.db"
    # Set to redis on Render. SQLite is intentionally retained for local development/tests.
    STORAGE_BACKEND: str = "sqlite"
    UPSTASH_REDIS_REST_URL: str = ""
    UPSTASH_REDIS_REST_TOKEN: str = ""
    ENV: str = "development"

    SESSION_MAX_AGE: int = 60 * 60 * 24 * 14
    OFFLINE_AFTER_SECONDS: int = 45
    COMMAND_TIMEOUT_SECONDS: float = 25.0
    DOWNLOAD_TIMEOUT_SECONDS: float = 60.0 * 30
    LOGIN_MAX_ATTEMPTS: int = 10
    LOGIN_WINDOW_SECONDS: int = 15 * 60
    HEARTBEAT_STALE_SECONDS: int = 45
    SCREEN_QUEUE_SIZE: int = 2
    TRANSFER_QUEUE_SIZE: int = 32
    MAX_CHUNK_BYTES: int = 64 * 1024

    @property
    def is_production(self) -> bool:
        return self.ENV.strip().lower() == "production"

    @property
    def cookie_secure(self) -> bool:
        return self.is_production

    @property
    def uses_redis(self) -> bool:
        return self.STORAGE_BACKEND.strip().lower() == "redis"


@lru_cache
def get_settings() -> Settings:
    return Settings()


def reset_settings_cache() -> None:
    get_settings.cache_clear()
