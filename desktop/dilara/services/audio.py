"""
Mikrofon yakalama ve VAD (voice activity detection).

Kullanıcı konuştuğu süre boyunca ses yakalar; sessizlik geldiğinde
stop eder. Sonuç tek bir 16-bit PCM mono numpy array olarak döner.

NOT: Burada hafif bir enerji tabanlı VAD kullanılıyor (RMS eşiği).
Daha doğru sonuç için Silero VAD entegre edilebilir; minimum
bağımlılıkla başlamak için bu yeterli.
"""

from __future__ import annotations

import asyncio
import queue
from dataclasses import dataclass
from typing import Optional

import numpy as np
import sounddevice as sd

from dilara.core.logging import logger


SAMPLE_RATE = 16000
CHANNELS = 1
DTYPE = "int16"
BLOCK_MS = 30                  # Her blok 30 ms
MAX_RECORD_SECONDS = 30.0      # Güvenlik tavanı
MAX_WAIT_FOR_SPEECH_S = 8.0    # Konuşma başlamazsa bu kadar saniye bekle


@dataclass
class CaptureResult:
    samples: np.ndarray         # int16 mono
    sample_rate: int = SAMPLE_RATE
    duration_s: float = 0.0


class MicrophoneCapture:
    """Mikrofon yakalama yardımcısı (VAD'li)."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE,
        silence_threshold: float = 150.0,    # RMS (int16) — düşük = daha hassas
        silence_duration_s: float = 1.2,
        min_speech_s: float = 0.3,
        pre_buffer_s: float = 0.3,
    ) -> None:
        self.sample_rate = sample_rate
        self.silence_threshold = silence_threshold
        self.silence_duration_s = silence_duration_s
        self.min_speech_s = min_speech_s
        self.pre_buffer_s = pre_buffer_s

    async def record_until_silence(self) -> CaptureResult:
        """Konuşma başlayana kadar bekler, bitince çıkar."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._record_blocking)

    def _record_blocking(self) -> CaptureResult:
        block_size = int(self.sample_rate * BLOCK_MS / 1000)
        audio_chunks: list[np.ndarray] = []
        silence_blocks_needed = int(self.silence_duration_s * 1000 / BLOCK_MS)
        max_blocks = int(MAX_RECORD_SECONDS * 1000 / BLOCK_MS)

        speech_started = False
        silence_count = 0
        block_count = 0
        no_speech_blocks = 0
        max_wait_blocks = int(MAX_WAIT_FOR_SPEECH_S * 1000 / BLOCK_MS)
        peak_rms = 0.0

        try:
            dev = sd.query_devices(kind="input")
            logger.info(f"Mikrofon kaydı başladı. Giriş cihazı: {dev['name']}")
        except Exception:
            logger.info("Mikrofon kaydı başladı.")

        q: queue.Queue[np.ndarray] = queue.Queue()

        def callback(indata, frames, time_info, status):  # noqa: ANN001
            if status:
                logger.debug(f"sounddevice status: {status}")
            q.put(indata.copy())

        try:
            with sd.InputStream(
                samplerate=self.sample_rate,
                channels=CHANNELS,
                dtype=DTYPE,
                blocksize=block_size,
                callback=callback,
            ):
                while block_count < max_blocks:
                    try:
                        block = q.get(timeout=1.0)
                    except queue.Empty:
                        continue
                    block_count += 1

                    rms = float(np.sqrt(np.mean(block.astype(np.float32) ** 2)))
                    if rms > peak_rms:
                        peak_rms = rms
                    is_speech = rms > self.silence_threshold

                    if is_speech:
                        speech_started = True
                        silence_count = 0
                        no_speech_blocks = 0
                        audio_chunks.append(block.flatten())
                    else:
                        if speech_started:
                            silence_count += 1
                            audio_chunks.append(block.flatten())
                            if silence_count >= silence_blocks_needed:
                                break
                        else:
                            no_speech_blocks += 1
                            if no_speech_blocks >= max_wait_blocks:
                                logger.debug("Konuşma algılanmadı, zaman aşımı.")
                                break
        except Exception as e:
            logger.error(f"Mikrofon yakalama hatası: {e}")
            return CaptureResult(samples=np.zeros(0, dtype=np.int16))

        logger.info(
            f"Kayıt bitti. En yüksek ses (RMS): {peak_rms:.1f} "
            f"(eşik: {self.silence_threshold}). Konuşma algılandı: {speech_started}"
        )
        if peak_rms < 1.0:
            logger.warning(
                "Mikrofondan hiç ses gelmedi (RMS≈0). macOS mikrofon izni "
                "verilmemiş olabilir veya yanlış giriş cihazı seçili."
            )

        if not audio_chunks:
            return CaptureResult(samples=np.zeros(0, dtype=np.int16))

        samples = np.concatenate(audio_chunks)
        duration = len(samples) / self.sample_rate

        if duration < self.min_speech_s:
            logger.debug(f"Çok kısa: {duration:.2f}s — yok sayılıyor")
            return CaptureResult(samples=np.zeros(0, dtype=np.int16))

        return CaptureResult(samples=samples, duration_s=duration)
