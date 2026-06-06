"""
Ana asistan motoru.

Ses ve metin girdisini alır, LLM'i çağırır, gerekirse tool çalıştırır,
cevabı seslendirir, hafızayı günceller.

Akış:

    user_text
       │
       ▼
   personality.detect_mode_command()  → mod değiştir
       │
       ▼
   memory.summary()  →  system_prompt'a göm
       │
       ▼
   llm.chat(messages, tools=...)
       │
       ├─ tool_calls varsa → ToolRegistry.call() → sonucu LLM'e döndür → tekrar çağır
       │
       ▼
   final text
       │
       ├─ TTS → ses dosyası → çal
       │
       ▼
   memory.extract_from_turn() (arka planda)
"""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Optional

from dilara.core.config import Settings
from dilara.core.events import EventBus
from dilara.core.logging import logger
from dilara.core.permissions import Permission, PermissionManager
from dilara.modules.personality import Personality
from dilara.modules.tools import ToolRegistry
from dilara.services.llm.base import ChatMessage, LLMBackend
from dilara.services.memory.extractor import MemoryExtractor
from dilara.services.memory.short_term import ShortTermMemory
from dilara.services.memory.store import MemoryStore
from dilara.services.tts.base import TTSBackend
from dilara.services.tts.player import play_file


class Assistant:
    def __init__(
        self,
        settings: Settings,
        permissions: PermissionManager,
        events: EventBus,
        llm: LLMBackend,
        tts: TTSBackend,
        memory: MemoryStore,
        personality: Personality,
        tools: ToolRegistry,
        memory_extractor: Optional[MemoryExtractor] = None,
        short_term: Optional[ShortTermMemory] = None,
    ) -> None:
        self.settings = settings
        self.permissions = permissions
        self.events = events
        self.llm = llm
        self.tts = tts
        self.memory = memory
        self.personality = personality
        self.tools = tools
        self.short_term = short_term or ShortTermMemory(max_turns=20)
        self.extractor = memory_extractor

    # --- Ana giriş noktası ---
    async def respond(self, user_text: str, *, speak: bool = True) -> str:
        """
        Tek bir kullanıcı turuna cevap ver.

        Args:
            user_text: Kullanıcının yazdığı/söylediği metin.
            speak: True ise sesle de oynat.
        Returns:
            Asistanın metin cevabı.
        """
        user_text = (user_text or "").strip()
        if not user_text:
            return ""

        # Mod değiştirme komutu mu?
        mode_change = self.personality.detect_mode_command(user_text)
        if mode_change is not None:
            self.personality.set_mode(mode_change)
            self.events.emit("mode.changed", mode=mode_change)
            confirm = (
                "Komut alındı. Ciddi moda geçiyorum."
                if mode_change == "serious"
                else "Tamam, normal moda dönüyorum."
            )
            self.short_term.add("user", user_text)
            self.short_term.add("assistant", confirm)
            if speak:
                await self._speak(confirm)
            return confirm

        # Hafıza özetini güncelle
        try:
            summary = await self.memory.summary(max_items=10)
            self.personality.update_memory_summary(summary)
        except Exception as e:
            logger.debug(f"Hafıza özeti hatası: {e}")

        # Mesajları hazırla
        self.short_term.add("user", user_text)
        messages: list[ChatMessage] = [
            ChatMessage(role="system", content=self.personality.system_prompt())
        ]
        messages.extend(self.short_term.messages())

        # LLM döngüsü (tool calling olabilir)
        self.events.emit("llm.thinking")
        text_response = await self._llm_loop(messages)

        self.short_term.add("assistant", text_response)
        self.events.emit("llm.response", text=text_response)

        # Konuş
        if speak and text_response:
            await self._speak(text_response)

        # Arka planda hafıza çıkar
        if self.extractor and self.permissions.is_granted(Permission.MEMORY_WRITE):
            asyncio.create_task(
                self._safe_extract(user_text, text_response)
            )

        return text_response

    async def _llm_loop(
        self, messages: list[ChatMessage], max_iterations: int = 4
    ) -> str:
        """LLM'i tool calling döngüsüyle çalıştır."""
        tools = self.tools.as_llm_tools()
        for i in range(max_iterations):
            try:
                resp = await self.llm.chat(
                    messages,
                    model=self.settings.llm_model,
                    temperature=0.7 if self.personality.mode == "normal" else 0.3,
                    max_tokens=800,
                    tools=tools,
                )
            except Exception as e:
                logger.error(f"LLM çağrısı başarısız: {e}")
                return "Şu an düşünmekte zorluk yaşıyorum, bir saniye sonra tekrar dener misin?"

            if not resp.tool_calls:
                return resp.text or "Anladım."

            # Tool çağrısı varsa işle
            messages.append(
                ChatMessage(
                    role="assistant",
                    content=resp.text or "",
                    tool_calls=[
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {
                                "name": tc["name"],
                                "arguments": tc["arguments"]
                                if isinstance(tc["arguments"], str)
                                else json.dumps(tc["arguments"]),
                            },
                        }
                        for tc in resp.tool_calls
                    ],
                )
            )

            for tc in resp.tool_calls:
                args = tc["arguments"]
                if isinstance(args, str):
                    try:
                        args = json.loads(args) if args.strip() else {}
                    except Exception:
                        args = {}
                logger.info(f"Tool çağrısı: {tc['name']}({args})")
                self.events.emit("tool.call", name=tc["name"], args=args)
                result = await self.tools.call(tc["name"], args or {})
                self.events.emit("tool.result", name=tc["name"], result=result)
                messages.append(
                    ChatMessage(
                        role="tool",
                        content=result,
                        tool_call_id=tc["id"],
                        name=tc["name"],
                    )
                )

        return "Birden çok adım denedim ama net cevaba ulaşamadım."

    async def _speak(self, text: str) -> None:
        if not text.strip():
            return
        try:
            output = self.settings.data_dir / "tts_out.mp3"
            self.events.emit("tts.start", text=text)
            await self.tts.synthesize(text, output)
            await play_file(output)
            self.events.emit("tts.end")
        except Exception as e:
            logger.error(f"TTS hatası: {e}")

    async def _safe_extract(self, user_text: str, assistant_text: str) -> None:
        try:
            await self.extractor.extract_from_turn(  # type: ignore[union-attr]
                user_text, assistant_text
            )
        except Exception as e:
            logger.debug(f"Hafıza çıkarımı hatası: {e}")

    def reset_conversation(self) -> None:
        self.short_term.clear()
