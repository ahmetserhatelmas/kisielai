"""LLM provider seçici + fallback wrapper."""

from __future__ import annotations

from typing import Optional

from dilara.core.config import Settings
from dilara.core.logging import logger
from dilara.services.llm.base import (
    ChatMessage,
    ChatResponse,
    LLMBackend,
    Tool,
)


def _build_single(provider: str, settings: Settings) -> Optional[LLMBackend]:
    if provider == "openai" and settings.openai_api_key:
        from dilara.services.llm.openai_backend import OpenAIBackend

        return OpenAIBackend(api_key=settings.openai_api_key)
    if provider == "anthropic" and settings.anthropic_api_key:
        from dilara.services.llm.anthropic_backend import AnthropicBackend

        return AnthropicBackend(api_key=settings.anthropic_api_key)
    if provider == "gemini" and settings.gemini_api_key:
        from dilara.services.llm.gemini_backend import GeminiBackend

        return GeminiBackend(api_key=settings.gemini_api_key)
    return None


class FallbackLLM(LLMBackend):
    """Birincil LLM çökerse otomatik ikinciye geçer."""

    name = "fallback"

    def __init__(
        self,
        primary: LLMBackend,
        primary_model: str,
        fallback: Optional[LLMBackend] = None,
        fallback_model: str = "",
    ) -> None:
        self.primary = primary
        self.primary_model = primary_model
        self.fallback = fallback
        self.fallback_model = fallback_model

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        model: str = "",
        temperature: float = 0.7,
        max_tokens: int = 1024,
        tools: Optional[list[Tool]] = None,
    ) -> ChatResponse:
        try:
            return await self.primary.chat(
                messages,
                model=model or self.primary_model,
                temperature=temperature,
                max_tokens=max_tokens,
                tools=tools,
            )
        except Exception as e:
            logger.warning(
                f"Birincil LLM ({self.primary.name}) hata verdi: {e}. "
                f"Fallback'e geçiliyor."
            )
            if not self.fallback:
                raise
            return await self.fallback.chat(
                messages,
                model=self.fallback_model or model,
                temperature=temperature,
                max_tokens=max_tokens,
                tools=tools,
            )


def build_llm(settings: Settings) -> LLMBackend:
    """Settings'ten LLM oluştur. Fallback varsa onu da kur."""
    primary = _build_single(settings.llm_provider, settings)
    if primary is None:
        raise RuntimeError(
            f"LLM sağlayıcısı '{settings.llm_provider}' için API anahtarı yok."
        )

    fallback = None
    if (
        settings.llm_fallback_provider
        and settings.llm_fallback_provider != "none"
    ):
        fallback = _build_single(settings.llm_fallback_provider, settings)

    return FallbackLLM(
        primary=primary,
        primary_model=settings.llm_model,
        fallback=fallback,
        fallback_model=settings.llm_fallback_model,
    )
