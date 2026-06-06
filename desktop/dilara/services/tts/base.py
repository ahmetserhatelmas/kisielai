"""TTS soyut taban sınıfı."""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import AsyncIterator, Optional


class TTSBackend(ABC):
    """Tüm TTS sağlayıcılarının uyduğu arayüz."""

    @abstractmethod
    async def synthesize(self, text: str, output_path: Path) -> Path:
        """Metni sese dönüştürüp dosyaya yazar, dosya yolunu döner."""

    async def stream(self, text: str) -> AsyncIterator[bytes]:
        """Streaming destekleyen sağlayıcılar override eder."""
        raise NotImplementedError
