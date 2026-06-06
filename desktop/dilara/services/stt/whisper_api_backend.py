"""OpenAI Whisper API tabanlı STT (online)."""

from __future__ import annotations

import io
import wave

import numpy as np

from dilara.core.logging import logger
from dilara.services.stt.base import STTBackend, TranscriptionResult


class WhisperAPISTT(STTBackend):
    def __init__(self, api_key: str, model: str = "whisper-1") -> None:
        self.api_key = api_key
        self.model = model
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from openai import AsyncOpenAI  # type: ignore

        self._client = AsyncOpenAI(api_key=self.api_key)

    async def transcribe(
        self,
        samples: np.ndarray,
        sample_rate: int = 16000,
        language: str = "tr",
    ) -> TranscriptionResult:
        if samples.size == 0:
            return TranscriptionResult(text="")
        self._ensure_client()

        wav_bytes = _samples_to_wav_bytes(samples, sample_rate)
        try:
            response = await self._client.audio.transcriptions.create(  # type: ignore
                model=self.model,
                file=("speech.wav", wav_bytes, "audio/wav"),
                language=language,
            )
            return TranscriptionResult(
                text=getattr(response, "text", "") or "",
                language=language,
                duration_s=len(samples) / sample_rate,
                confidence=1.0,
            )
        except Exception as e:
            logger.error(f"Whisper API hatası: {e}")
            return TranscriptionResult(text="")


def _samples_to_wav_bytes(samples: np.ndarray, sample_rate: int) -> bytes:
    if samples.dtype != np.int16:
        samples = (samples * 32767).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(samples.tobytes())
    return buf.getvalue()
