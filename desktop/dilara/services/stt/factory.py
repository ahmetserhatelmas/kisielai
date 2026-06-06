"""STT sağlayıcı seçici."""

from __future__ import annotations

from dilara.core.config import Settings
from dilara.services.stt.base import STTBackend


def build_stt(settings: Settings) -> STTBackend:
    provider = settings.stt_provider
    if provider == "whisper-api":
        from dilara.services.stt.whisper_api_backend import WhisperAPISTT

        return WhisperAPISTT(api_key=settings.openai_api_key)
    # Default
    from dilara.services.stt.faster_whisper_backend import FasterWhisperSTT

    return FasterWhisperSTT(
        model_size=settings.stt_model, language=settings.stt_language
    )
