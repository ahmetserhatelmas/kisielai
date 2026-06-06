"""Anthropic Claude sağlayıcısı."""

from __future__ import annotations

from typing import Any, AsyncIterator, Optional

from dilara.core.logging import logger
from dilara.services.llm.base import (
    ChatMessage,
    ChatResponse,
    LLMBackend,
    Tool,
)


class AnthropicBackend(LLMBackend):
    name = "anthropic"

    def __init__(self, api_key: str) -> None:
        self.api_key = api_key
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from anthropic import AsyncAnthropic  # type: ignore

        self._client = AsyncAnthropic(api_key=self.api_key)

    @staticmethod
    def _split_system(messages: list[ChatMessage]) -> tuple[str, list[dict[str, Any]]]:
        system_parts: list[str] = []
        rest: list[dict[str, Any]] = []
        for m in messages:
            if m.role == "system":
                system_parts.append(m.content)
            else:
                role = m.role if m.role in {"user", "assistant"} else "user"
                rest.append({"role": role, "content": m.content})
        return "\n\n".join(system_parts), rest

    async def chat(
        self,
        messages: list[ChatMessage],
        *,
        model: str,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        tools: Optional[list[Tool]] = None,
    ) -> ChatResponse:
        self._ensure_client()
        system, msgs = self._split_system(messages)
        try:
            kwargs: dict[str, Any] = {
                "model": model,
                "max_tokens": max_tokens,
                "temperature": temperature,
                "system": system or "",
                "messages": msgs,
            }
            if tools:
                kwargs["tools"] = [
                    {
                        "name": t.name,
                        "description": t.description,
                        "input_schema": t.parameters,
                    }
                    for t in tools
                ]
            resp = await self._client.messages.create(**kwargs)  # type: ignore
            text_parts: list[str] = []
            tool_calls: list[dict[str, Any]] = []
            for block in resp.content:
                if getattr(block, "type", "") == "text":
                    text_parts.append(block.text)
                elif getattr(block, "type", "") == "tool_use":
                    tool_calls.append(
                        {
                            "id": block.id,
                            "name": block.name,
                            "arguments": block.input,
                        }
                    )
            return ChatResponse(
                text="".join(text_parts),
                tool_calls=tool_calls,
                raw=resp,
                finish_reason=resp.stop_reason or "stop",
            )
        except Exception as e:
            logger.error(f"Anthropic chat hatası: {e}")
            raise

    async def stream(
        self,
        messages: list[ChatMessage],
        *,
        model: str,
        temperature: float = 0.7,
        max_tokens: int = 1024,
    ) -> AsyncIterator[str]:
        self._ensure_client()
        system, msgs = self._split_system(messages)
        async with self._client.messages.stream(  # type: ignore
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            system=system or "",
            messages=msgs,
        ) as stream:
            async for text in stream.text_stream:
                yield text
