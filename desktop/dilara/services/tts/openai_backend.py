"""OpenAI TTS sağlayıcısı."""

from __future__ import annotations

from pathlib import Path

from dilara.core.logging import logger
from dilara.services.tts.base import TTSBackend


class OpenAITTS(TTSBackend):
    def __init__(
        self,
        api_key: str,
        voice: str = "nova",
        model: str = "gpt-4o-mini-tts",
    ) -> None:
        self.api_key = api_key
        self.voice = voice
        self.model = model
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from openai import AsyncOpenAI  # type: ignore

        self._client = AsyncOpenAI(api_key=self.api_key)

    async def synthesize(self, text: str, output_path: Path) -> Path:
        self._ensure_client()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            async with self._client.audio.speech.with_streaming_response.create(  # type: ignore
                model=self.model,
                voice=self.voice,
                input=text,
                response_format="mp3",
            ) as response:
                with open(output_path, "wb") as f:
                    async for chunk in response.iter_bytes():
                        f.write(chunk)
            return output_path
        except Exception as e:
            logger.error(f"OpenAI TTS hatası: {e}")
            raise
