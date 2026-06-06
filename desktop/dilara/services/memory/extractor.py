"""
Konuşmadan otomatik hafıza çıkarımı.

Her konuşma turundan sonra LLM'e küçük bir görev veriyoruz: bu
konuşmadan kalıcı olarak hatırlanması gereken bilgi var mı? Varsa
JSON olarak çıkar.

Çıkarılan kayıtlar MemoryStore'a yazılır.
"""

from __future__ import annotations

import json
from typing import Optional

from dilara.core.logging import logger
from dilara.services.llm.base import ChatMessage, LLMBackend
from dilara.services.memory.store import MemoryRecord, MemoryStore


_EXTRACT_PROMPT = """Aşağıdaki konuşma turundan kullanıcı hakkında uzun vadeli hatırlanması gereken bilgileri JSON listesi olarak çıkar.

Sadece şunları kaydet:
- Kullanıcının kişisel tercihleri (sevdiği şeyler, alışkanlıklar)
- Rutinleri (uyku saati, iş düzeni vb.)
- Önemli olaylar ve tarihler
- Yakın kişiler / arkadaşlar
- Hitap tercihleri

KAYDETME:
- Genel sohbet
- Geçici durumlar
- Kullanıcıyla ilgili olmayan bilgiler

Çıktı formatı (sadece JSON, başka hiçbir şey yazma):
{"items": [
  {"text": "...", "category": "preference|routine|event|person|note", "importance": 0.0-1.0}
]}

Eğer hatırlanacak hiçbir şey yoksa: {"items": []}

Konuşma turu:
KULLANICI: {user_text}
DİLARA: {assistant_text}
"""


class MemoryExtractor:
    def __init__(
        self,
        llm: LLMBackend,
        store: MemoryStore,
        model: str,
    ) -> None:
        self.llm = llm
        self.store = store
        self.model = model

    async def extract_from_turn(
        self, user_text: str, assistant_text: str
    ) -> list[MemoryRecord]:
        if not user_text.strip():
            return []

        prompt = _EXTRACT_PROMPT.format(
            user_text=user_text[:2000],
            assistant_text=assistant_text[:2000],
        )
        try:
            resp = await self.llm.chat(
                [ChatMessage(role="user", content=prompt)],
                model=self.model,
                temperature=0.0,
                max_tokens=512,
            )
        except Exception as e:
            logger.debug(f"Hafıza çıkarımı LLM hatası: {e}")
            return []

        text = resp.text.strip()
        if not text:
            return []

        # JSON'u temizle
        if text.startswith("```"):
            text = text.strip("`")
            if text.lower().startswith("json"):
                text = text[4:].strip()

        try:
            data = json.loads(text)
        except Exception:
            return []

        items = data.get("items", [])
        records: list[MemoryRecord] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            mem_text = (item.get("text") or "").strip()
            if not mem_text:
                continue
            category = item.get("category", "note")
            importance = float(item.get("importance", 0.5))
            try:
                rec = await self.store.remember(
                    text=mem_text,
                    category=category,
                    importance=max(0.0, min(1.0, importance)),
                )
                records.append(rec)
            except Exception as e:
                logger.debug(f"Hafıza kaydetme hatası: {e}")
        return records
