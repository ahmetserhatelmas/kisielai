"""STT için soyut taban sınıf."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

import numpy as np


@dataclass
class TranscriptionResult:
    text: str
    language: str = "tr"
    duration_s: float = 0.0
    confidence: float = 0.0


class STTBackend(ABC):
    """Tüm STT sağlayıcılarının uyduğu arayüz."""

    @abstractmethod
    async def transcribe(
        self,
        samples: np.ndarray,
        sample_rate: int = 16000,
        language: str = "tr",
    ) -> TranscriptionResult:
        ...
