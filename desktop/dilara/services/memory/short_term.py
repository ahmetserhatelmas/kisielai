"""Kısa süreli hafıza — yalnızca aktif konuşma turları."""

from __future__ import annotations

from collections import deque
from typing import Iterable

from dilara.services.llm.base import ChatMessage


class ShortTermMemory:
    """Sliding window konuşma geçmişi."""

    def __init__(self, max_turns: int = 24) -> None:
        self._buffer: deque[ChatMessage] = deque(maxlen=max_turns)

    def add(self, role: str, content: str) -> None:
        self._buffer.append(ChatMessage(role=role, content=content))  # type: ignore[arg-type]

    def add_message(self, message: ChatMessage) -> None:
        self._buffer.append(message)

    def messages(self) -> list[ChatMessage]:
        return list(self._buffer)

    def clear(self) -> None:
        self._buffer.clear()

    def __len__(self) -> int:
        return len(self._buffer)
