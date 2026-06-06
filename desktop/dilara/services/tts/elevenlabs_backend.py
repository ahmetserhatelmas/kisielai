"""ElevenLabs — premium kalitede TTS (ücretli)."""

from __future__ import annotations

from pathlib import Path

from dilara.core.logging import logger
from dilara.services.tts.base import TTSBackend


class ElevenLabsTTS(TTSBackend):
    def __init__(
        self,
        api_key: str,
        voice_id: str,
        model_id: str = "eleven_multilingual_v2",
    ) -> None:
        self.api_key = api_key
        self.voice_id = voice_id
        self.model_id = model_id
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from elevenlabs.client import ElevenLabs  # type: ignore

        self._client = ElevenLabs(api_key=self.api_key)

    async def synthesize(self, text: str, output_path: Path) -> Path:
        import asyncio

        return await asyncio.get_event_loop().run_in_executor(
            None, self._sync_synthesize, text, output_path
        )

    def _sync_synthesize(self, text: str, output_path: Path) -> Path:
        self._ensure_client()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            audio_iter = self._client.text_to_speech.convert(  # type: ignore
                voice_id=self.voice_id,
                text=text,
                model_id=self.model_id,
                output_format="mp3_44100_128",
            )
            with open(output_path, "wb") as f:
                for chunk in audio_iter:
                    if isinstance(chunk, (bytes, bytearray)):
                        f.write(chunk)
            return output_path
        except Exception as e:
            logger.error(f"ElevenLabs hatası: {e}")
            raise
