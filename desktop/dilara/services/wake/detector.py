"""
Wake word detector (openWakeWord tabanlı).

* Tamamen lokal çalışır, internet gerektirmez.
* Düşük güç tüketimli (CPU üzerinde 30 ms'lik bloklar).
* "Hey Dilara" / "Selam Dilara" gibi özel kelimeler için openWakeWord
  yerine Vosk + keyword spotting de eklenebilir; ilk MVP için
  openWakeWord built-in modellerinden ``hey_jarvis`` kullanılabilir,
  ileride özel model eğitilir.

Kullanım:
    detector = WakeWordDetector()
    detector.start(on_wake=lambda: print("Algılandı!"))
    ...
    detector.stop()
"""

from __future__ import annotations

import asyncio
import threading
from typing import Awaitable, Callable, Optional, Union

import numpy as np
import sounddevice as sd

from dilara.core.logging import logger


WakeCallback = Union[Callable[[], None], Callable[[], Awaitable[None]]]


class WakeWordDetector:
    """openWakeWord sarmalayıcısı."""

    SAMPLE_RATE = 16000
    BLOCK_SIZE = 1280  # 80 ms @ 16kHz (openWakeWord standart frame)

    def __init__(
        self,
        keyword: str = "hey_jarvis",
        threshold: float = 0.5,
        loop: Optional[asyncio.AbstractEventLoop] = None,
    ) -> None:
        self.keyword = keyword
        self.threshold = threshold
        self._loop = loop
        self._thread: Optional[threading.Thread] = None
        self._stop_flag = threading.Event()
        self._on_wake: Optional[WakeCallback] = None
        self._model = None

    # --- Yaşam döngüsü ---
    def start(self, on_wake: WakeCallback) -> None:
        """Wake word dinlemesini başlat."""
        if self._thread and self._thread.is_alive():
            logger.warning("Wake detector zaten çalışıyor.")
            return
        self._on_wake = on_wake
        self._stop_flag.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        logger.info(f"Wake word dinleniyor: '{self.keyword}' (eşik={self.threshold})")

    def stop(self) -> None:
        self._stop_flag.set()
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        logger.info("Wake word dinleme durduruldu.")

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    # --- İçsel ---
    def _ensure_model(self) -> None:
        if self._model is not None:
            return
        try:
            from openwakeword.model import Model  # type: ignore
        except Exception as e:
            logger.error(f"openwakeword yüklenemedi: {e}")
            raise

        try:
            self._model = Model(
                wakeword_models=[self.keyword],
                inference_framework="onnx",
            )
        except Exception:
            # Bilinen wake word'ler arasında değilse hey_jarvis'e düş
            logger.warning(
                f"'{self.keyword}' modeli bulunamadı, 'hey_jarvis' kullanılıyor."
            )
            self._model = Model(
                wakeword_models=["hey_jarvis"],
                inference_framework="onnx",
            )

    def _trigger(self) -> None:
        if not self._on_wake:
            return
        cb = self._on_wake
        try:
            result = cb()
            if asyncio.iscoroutine(result):
                if self._loop and self._loop.is_running():
                    asyncio.run_coroutine_threadsafe(result, self._loop)
                else:
                    asyncio.run(result)
        except Exception as e:
            logger.error(f"Wake callback hatası: {e}")

    def _run(self) -> None:
        try:
            self._ensure_model()
        except Exception:
            return

        try:
            with sd.InputStream(
                samplerate=self.SAMPLE_RATE,
                channels=1,
                dtype="int16",
                blocksize=self.BLOCK_SIZE,
            ) as stream:
                while not self._stop_flag.is_set():
                    audio, _ = stream.read(self.BLOCK_SIZE)
                    pcm = audio[:, 0] if audio.ndim > 1 else audio
                    pcm = pcm.astype(np.int16)
                    try:
                        scores = self._model.predict(pcm)  # type: ignore
                    except Exception as e:
                        logger.debug(f"Wake predict hatası: {e}")
                        continue
                    for kw, score in scores.items():
                        if score >= self.threshold:
                            logger.info(f"Wake word algılandı: {kw} ({score:.2f})")
                            self._trigger()
                            # Reset to avoid re-firing on the same buffer
                            try:
                                self._model.reset()  # type: ignore
                            except Exception:
                                pass
                            break
        except Exception as e:
            logger.error(f"Wake detector hatası: {e}")
