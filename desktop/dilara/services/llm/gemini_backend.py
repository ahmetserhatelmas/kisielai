"""Google Gemini sağlayıcısı."""

from __future__ import annotations

import asyncio
from typing import Any, AsyncIterator, Optional

from dilara.core.logging import logger
from dilara.services.llm.base import (
    ChatMessage,
    ChatResponse,
    LLMBackend,
    Tool,
)


class GeminiBackend(LLMBackend):
    name = "gemini"

    def __init__(self, api_key: str) -> None:
        self.api_key = api_key
        self._configured = False

    def _ensure(self) -> None:
        if self._configured:
            return
        import google.generativeai as genai  # type: ignore

        genai.configure(api_key=self.api_key)
        self._genai = genai
        self._configured = True

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        model: str,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        tools: Optional[list[Tool]] = None,
    ) -> ChatResponse:
        self._ensure()
        return await asyncio.get_event_loop().run_in_executor(
            None,
            self._sync_chat,
            messages,
            model,
            temperature,
            max_tokens,
        )

    def _sync_chat(
        self,
        messages: list[ChatMessage],
        model: str,
        temperature: float,
        max_tokens: int,
    ) -> ChatResponse:
        gen_cfg = self._genai.GenerationConfig(  # type: ignore
            temperature=temperature, max_output_tokens=max_tokens
        )
        system_text = "\n\n".join(m.content for m in messages if m.role == "system")
        history: list[dict[str, Any]] = []
        for m in messages:
            if m.role == "system":
                continue
            role = "user" if m.role == "user" else "model"
            history.append({"role": role, "parts": [m.content]})

        try:
            llm = self._genai.GenerativeModel(  # type: ignore
                model_name=model,
                system_instruction=system_text or None,
                generation_config=gen_cfg,
            )
            resp = llm.generate_content(history)
            text = getattr(resp, "text", "") or ""
            return ChatResponse(text=text, raw=resp)
        except Exception as e:
            logger.error(f"Gemini chat hatası: {e}")
            raise
