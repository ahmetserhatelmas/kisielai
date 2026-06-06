"""
Microsoft Edge TTS — bedava, çok yüksek kaliteli Türkçe sesler.

Türkçe sesler:
* tr-TR-EmelNeural   — kadın (önerilen)
* tr-TR-AhmetNeural  — erkek
"""

from __future__ import annotations

from pathlib import Path

from dilara.core.logging import logger
from dilara.services.tts.base import TTSBackend


class EdgeTTS(TTSBackend):
    def __init__(
        self,
        voice: str = "tr-TR-EmelNeural",
        rate: str = "+5%",
        pitch: str = "+0Hz",
    ) -> None:
        self.voice = voice
        self.rate = rate
        self.pitch = pitch

    async def synthesize(self, text: str, output_path: Path) -> Path:
        try:
            import edge_tts  # type: ignore
        except Exception as e:
            logger.error(f"edge-tts yüklenemedi: {e}")
            raise

        output_path.parent.mkdir(parents=True, exist_ok=True)
        communicate = edge_tts.Communicate(
            text=text, voice=self.voice, rate=self.rate, pitch=self.pitch
        )
        await communicate.save(str(output_path))
        return output_path
