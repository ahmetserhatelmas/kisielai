"""
Konfigürasyon yönetimi.

.env dosyasından ve ortam değişkenlerinden ayarları okur. Tüm
ayarlar tip güvenli (Pydantic) ve tek bir yerden erişilebilir.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "dilara" / "data"
ASSETS_DIR = PROJECT_ROOT / "dilara" / "assets"


class Settings(BaseSettings):
    """Tüm uygulama ayarları."""

    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        env_file_encoding="utf-8",
        env_prefix="",
        extra="ignore",
        case_sensitive=False,
    )

    # --- Kullanıcı ---
    user_name: str = Field(default="Kullanıcı", alias="DILARA_USER_NAME")
    user_language: str = Field(default="tr", alias="DILARA_USER_LANGUAGE")

    # --- LLM ---
    llm_provider: Literal["openai", "anthropic", "gemini"] = Field(
        default="openai", alias="DILARA_LLM_PROVIDER"
    )
    llm_model: str = Field(default="gpt-4o", alias="DILARA_LLM_MODEL")
    llm_fallback_provider: Literal["openai", "anthropic", "gemini", "none"] = Field(
        default="none", alias="DILARA_LLM_FALLBACK_PROVIDER"
    )
    llm_fallback_model: str = Field(default="", alias="DILARA_LLM_FALLBACK_MODEL")

    openai_api_key: str = Field(default="", alias="OPENAI_API_KEY")
    anthropic_api_key: str = Field(default="", alias="ANTHROPIC_API_KEY")
    gemini_api_key: str = Field(default="", alias="GEMINI_API_KEY")

    # --- TTS ---
    tts_provider: Literal["elevenlabs", "openai", "edge"] = Field(
        default="edge", alias="DILARA_TTS_PROVIDER"
    )
    tts_voice: str = Field(default="tr-TR-EmelNeural", alias="DILARA_TTS_VOICE")
    tts_rate: str = Field(default="+5%", alias="DILARA_TTS_RATE")
    tts_pitch: str = Field(default="+0Hz", alias="DILARA_TTS_PITCH")
    elevenlabs_api_key: str = Field(default="", alias="ELEVENLABS_API_KEY")
    elevenlabs_voice_id: str = Field(default="", alias="ELEVENLABS_VOICE_ID")

    # --- STT ---
    stt_provider: Literal["whisper-api", "faster-whisper"] = Field(
        default="faster-whisper", alias="DILARA_STT_PROVIDER"
    )
    stt_model: str = Field(default="base", alias="DILARA_STT_MODEL")
    stt_language: str = Field(default="tr", alias="DILARA_STT_LANGUAGE")

    # --- Wake word ---
    wake_word: str = Field(default="hey_dilara", alias="DILARA_WAKE_WORD")
    wake_threshold: float = Field(default=0.5, alias="DILARA_WAKE_THRESHOLD")
    wake_enabled: bool = Field(default=True, alias="DILARA_WAKE_ENABLED")

    # --- Backend ---
    backend_url: str = Field(default="http://localhost:8000", alias="DILARA_BACKEND_URL")
    backend_token: str = Field(default="", alias="DILARA_BACKEND_TOKEN")
    sync_enabled: bool = Field(default=False, alias="DILARA_SYNC_ENABLED")

    # --- Güvenlik ---
    encryption_key: str = Field(default="", alias="DILARA_ENCRYPTION_KEY")

    # --- Davranış ---
    default_mode: Literal["normal", "serious"] = Field(
        default="normal", alias="DILARA_DEFAULT_MODE"
    )
    log_level: str = Field(default="INFO", alias="DILARA_LOG_LEVEL")

    # --- Yollar ---
    @property
    def data_dir(self) -> Path:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        return DATA_DIR

    @property
    def memory_dir(self) -> Path:
        path = self.data_dir / "memory"
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def profile_dir(self) -> Path:
        path = self.data_dir / "profiles"
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def log_dir(self) -> Path:
        path = self.data_dir / "logs"
        path.mkdir(parents=True, exist_ok=True)
        return path

    @property
    def assets_dir(self) -> Path:
        ASSETS_DIR.mkdir(parents=True, exist_ok=True)
        return ASSETS_DIR

    def llm_provider_has_key(self, provider: str) -> bool:
        return {
            "openai": bool(self.openai_api_key),
            "anthropic": bool(self.anthropic_api_key),
            "gemini": bool(self.gemini_api_key),
        }.get(provider, False)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
