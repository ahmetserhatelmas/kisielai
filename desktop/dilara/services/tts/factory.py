"""TTS sağlayıcı seçici."""

from __future__ import annotations

from dilara.core.config import Settings
from dilara.services.tts.base import TTSBackend


def build_tts(settings: Settings) -> TTSBackend:
    provider = settings.tts_provider
    if provider == "elevenlabs":
        from dilara.services.tts.elevenlabs_backend import ElevenLabsTTS

        return ElevenLabsTTS(
            api_key=settings.elevenlabs_api_key,
            voice_id=settings.elevenlabs_voice_id,
        )
    if provider == "openai":
        from dilara.services.tts.openai_backend import OpenAITTS

        return OpenAITTS(api_key=settings.openai_api_key, voice="nova")
    # Default: edge (bedava)
    from dilara.services.tts.edge_backend import EdgeTTS

    return EdgeTTS(
        voice=settings.tts_voice, rate=settings.tts_rate, pitch=settings.tts_pitch
    )
