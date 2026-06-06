"""Backend konfigürasyonu."""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    database_url: str = "sqlite+aiosqlite:///./dilara_backend.db"
    secret_key: str = "change-me-32-bytes"
    access_token_expire_minutes: int = 43200  # 30 gün
    algorithm: str = "HS256"
    dilara_api_key: str = ""
    allowed_origins: str = "http://localhost:3000,http://localhost:8000"
    log_level: str = "INFO"

    @property
    def allowed_origins_list(self) -> list[str]:
        return [o.strip() for o in self.allowed_origins.split(",") if o.strip()]


@lru_cache(maxsize=1)
def _get() -> Settings:
    return Settings()


settings = _get()
