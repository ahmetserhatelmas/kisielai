"""Faster-Whisper tabanlı lokal STT (offline çalışır)."""

from __future__ import annotations

import asyncio
import io
from typing import Optional

import numpy as np
import soundfile as sf

from dilara.core.logging import logger
from dilara.services.stt.base import STTBackend, TranscriptionResult


class FasterWhisperSTT(STTBackend):
    def __init__(self, model_size: str = "base", language: str = "tr") -> None:
        self.model_size = model_size
        self.language = language
        self._model = None
        self._lock = asyncio.Lock()

    def _ensure_model(self) -> None:
        if self._model is not None:
            return
        try:
            from faster_whisper import WhisperModel  # type: ignore
        except Exception as e:
            logger.error(f"faster-whisper yüklenemedi: {e}")
            raise
        logger.info(f"Whisper modeli yükleniyor: {self.model_size}")
        self._model = WhisperModel(
            self.model_size, device="auto", compute_type="auto"
        )

    async def transcribe(
        self,
        samples: np.ndarray,
        sample_rate: int = 16000,
        language: str = "tr",
    ) -> TranscriptionResult:
        if samples.size == 0:
            return TranscriptionResult(text="")

        async with self._lock:
            return await asyncio.get_event_loop().run_in_executor(
                None, self._sync_transcribe, samples, sample_rate, language
            )

    def _sync_transcribe(
        self, samples: np.ndarray, sample_rate: int, language: str
    ) -> TranscriptionResult:
        self._ensure_model()
        # int16 -> float32
        if samples.dtype == np.int16:
            audio = samples.astype(np.float32) / 32768.0
        else:
            audio = samples.astype(np.float32)

        try:
            segments, info = self._model.transcribe(  # type: ignore
                audio,
                language=language or self.language,
                vad_filter=True,
                beam_size=1,
            )
            text = " ".join(seg.text for seg in segments).strip()
            return TranscriptionResult(
                text=text,
                language=info.language if info else (language or self.language),
                duration_s=info.duration if info else 0.0,
                confidence=1.0,
            )
        except Exception as e:
            logger.error(f"Whisper transcribe hatası: {e}")
            return TranscriptionResult(text="")
