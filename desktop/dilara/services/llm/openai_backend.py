"""OpenAI sağlayıcısı (Chat Completions + tool calling)."""

from __future__ import annotations

from typing import Any, AsyncIterator, Optional

from dilara.core.logging import logger
from dilara.services.llm.base import (
    ChatMessage,
    ChatResponse,
    LLMBackend,
    Tool,
)


class OpenAIBackend(LLMBackend):
    name = "openai"

    def __init__(self, api_key: str) -> None:
        self.api_key = api_key
        self._client = None

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        from openai import AsyncOpenAI  # type: ignore

        self._client = AsyncOpenAI(api_key=self.api_key)

    @staticmethod
    def _convert_messages(messages: list[ChatMessage]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        for m in messages:
            base: dict[str, Any] = {"role": m.role, "content": m.content}
            if m.name:
                base["name"] = m.name
            if m.tool_call_id:
                base["tool_call_id"] = m.tool_call_id
            if m.tool_calls:
                base["tool_calls"] = m.tool_calls
            out.append(base)
        return out

    @staticmethod
    def _convert_tools(tools: Optional[list[Tool]]) -> Optional[list[dict[str, Any]]]:
        if not tools:
            return None
        return [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters,
                },
            }
            for t in tools
        ]

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
        try:
            kwargs: dict[str, Any] = {
                "model": model,
                "messages": self._convert_messages(messages),
                "temperature": temperature,
                "max_tokens": max_tokens,
            }
            tool_payload = self._convert_tools(tools)
            if tool_payload:
                kwargs["tools"] = tool_payload
                kwargs["tool_choice"] = "auto"

            resp = await self._client.chat.completions.create(**kwargs)  # type: ignore
            choice = resp.choices[0]
            msg = choice.message
            tool_calls = []
            if getattr(msg, "tool_calls", None):
                for tc in msg.tool_calls:
                    tool_calls.append(
                        {
                            "id": tc.id,
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        }
                    )
            return ChatResponse(
                text=(msg.content or ""),
                tool_calls=tool_calls,
                raw=resp,
                finish_reason=choice.finish_reason or "stop",
            )
        except Exception as e:
            logger.error(f"OpenAI chat hatası: {e}")
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
        stream = await self._client.chat.completions.create(  # type: ignore
            model=model,
            messages=self._convert_messages(messages),
            temperature=temperature,
            max_tokens=max_tokens,
            stream=True,
        )
        async for chunk in stream:
            try:
                delta = chunk.choices[0].delta.content or ""
                if delta:
                    yield delta
            except Exception:
                continue
