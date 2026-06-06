"""Loguru tabanlı merkezi logger konfigürasyonu."""

from __future__ import annotations

import sys
from pathlib import Path

from loguru import logger as _logger


def configure_logging(log_dir: Path, level: str = "INFO") -> None:
    """Loguru'yu konsol + dosyaya yönlendir."""
    _logger.remove()
    _logger.add(
        sys.stderr,
        level=level,
        format=(
            "<green>{time:HH:mm:ss}</green> "
            "<level>{level: <8}</level> "
            "<cyan>{name}</cyan>:<cyan>{line}</cyan> | "
            "<level>{message}</level>"
        ),
        colorize=True,
    )
    _logger.add(
        log_dir / "dilara.log",
        level=level,
        rotation="5 MB",
        retention="14 days",
        encoding="utf-8",
        format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{line} | {message}",
    )


logger = _logger
