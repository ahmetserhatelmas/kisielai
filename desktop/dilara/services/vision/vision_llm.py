"""
Görselden anlam çıkarma — multimodal LLM çağrısı.

Şu an sadece OpenAI (gpt-4o) destekliyor; Anthropic ve Gemini için
sonradan eklenebilir (mimari hazır).
"""

from __future__ import annotations

import base64
from pathlib import Path
from typing import Optional

from dilara.core.config import Settings
from dilara.core.logging import logger


class VisionLLM:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from openai import AsyncOpenAI  # type: ignore

        if not self.settings.openai_api_key:
            raise RuntimeError("Görsel analiz için OpenAI API anahtarı gerekli.")
        self._client = AsyncOpenAI(api_key=self.settings.openai_api_key)

    async def describe(
        self,
        image_path: Path,
        prompt: str = "Bu görselde ne var? Türkçe ve kısa anlat.",
        model: str = "gpt-4o-mini",
    ) -> str:
        self._ensure_client()
        try:
            data = image_path.read_bytes()
        except Exception as e:
            logger.error(f"Görsel okuma hatası: {e}")
            return ""

        b64 = base64.b64encode(data).decode("ascii")
        suffix = image_path.suffix.lstrip(".").lower() or "png"
        if suffix == "jpg":
            suffix = "jpeg"

        try:
            resp = await self._client.chat.completions.create(  # type: ignore
                model=model,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/{suffix};base64,{b64}"
                                },
                            },
                        ],
                    }
                ],
                max_tokens=600,
            )
            return resp.choices[0].message.content or ""
        except Exception as e:
            logger.error(f"Görsel LLM hatası: {e}")
            return ""
