"""LLM sağlayıcıları için ortak arayüz."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Literal, Optional


Role = Literal["system", "user", "assistant", "tool"]


@dataclass
class ChatMessage:
    role: Role
    content: str
    name: Optional[str] = None
    tool_call_id: Optional[str] = None
    tool_calls: Optional[list[dict[str, Any]]] = None


@dataclass
class Tool:
    """LLM tool tanımı (OpenAI function calling formatında)."""

    name: str
    description: str
    parameters: dict[str, Any]
    handler: Any = None  # callable


@dataclass
class ChatResponse:
    text: str
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    raw: Optional[Any] = None
    finish_reason: str = "stop"


class LLMBackend(ABC):
    """Tüm LLM sağlayıcılarının uyduğu arayüz."""

    name: str = "abstract"

    @abstractmethod
    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        model: str,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        tools: Optional[list[Tool]] = None,
    ) -> ChatResponse:
        ...

    async def stream(
        self,
        messages: list[ChatMessage],
        *,
        model: str,
        temperature: float = 0.7,
        max_tokens: int = 1024,
    ) -> AsyncIterator[str]:
        """Streaming destekleyen sağlayıcılar override eder."""
        resp = await self.chat(
            messages, model=model, temperature=temperature, max_tokens=max_tokens
        )
        yield resp.text
