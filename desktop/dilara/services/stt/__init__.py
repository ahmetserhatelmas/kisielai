"""Speech-to-Text servisleri."""

from dilara.services.stt.base import STTBackend, TranscriptionResult
from dilara.services.stt.factory import build_stt

__all__ = ["STTBackend", "TranscriptionResult", "build_stt"]
