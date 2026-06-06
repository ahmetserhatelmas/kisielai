"""
Olay otobüsü (Event Bus).

Modüller birbirine doğrudan bağlanmadan, olaylar üzerinden konuşur.
Örnek olaylar:

* ``wake.detected``      — wake word algılandı
* ``audio.capture.start``
* ``audio.capture.end``
* ``stt.partial``
* ``stt.final``
* ``llm.thinking``
* ``llm.response``
* ``tts.start``
* ``tts.end``
* ``permission.changed``
* ``mode.changed``
* ``error``

Hem senkron (callback) hem async dinleyiciler desteklenir.
"""

from __future__ import annotations

import asyncio
import inspect
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Union


Listener = Union[Callable[["Event"], None], Callable[["Event"], Awaitable[None]]]


@dataclass
class Event:
    name: str
    payload: dict[str, Any] = field(default_factory=dict)


class EventBus:
    """Çok hafif bir pub/sub uygulaması."""

    def __init__(self) -> None:
        self._listeners: dict[str, list[Listener]] = defaultdict(list)

    def on(self, event_name: str, listener: Listener) -> Callable[[], None]:
        """Bir dinleyici kaydeder. Geri dönen fonksiyonu çağırarak siler."""
        self._listeners[event_name].append(listener)

        def _unsubscribe() -> None:
            try:
                self._listeners[event_name].remove(listener)
            except ValueError:
                pass

        return _unsubscribe

    def emit(self, event_name: str, **payload: Any) -> None:
        """Senkron olarak tüm dinleyicileri çağırır."""
        event = Event(name=event_name, payload=payload)
        for listener in list(self._listeners.get(event_name, [])):
            try:
                result = listener(event)
                if inspect.isawaitable(result):
                    # Async dinleyici varsa bir task'a sar
                    try:
                        loop = asyncio.get_event_loop()
                        if loop.is_running():
                            loop.create_task(result)  # type: ignore[arg-type]
                    except RuntimeError:
                        pass
            except Exception:
                # Dinleyiciler birbirini etkilememeli.
                pass

    async def emit_async(self, event_name: str, **payload: Any) -> None:
        """Async dinleyicileri await eder."""
        event = Event(name=event_name, payload=payload)
        coros = []
        for listener in list(self._listeners.get(event_name, [])):
            try:
                result = listener(event)
                if inspect.isawaitable(result):
                    coros.append(result)
            except Exception:
                pass
        if coros:
            await asyncio.gather(*coros, return_exceptions=True)
