"""Ses dosyasını sistemde çalan yardımcı."""

from __future__ import annotations

import asyncio
import platform
import subprocess
from pathlib import Path

from dilara.core.logging import logger


async def play_file(path: Path) -> None:
    """MP3 / WAV dosyasını çalar (cross-platform, blocking değil)."""
    await asyncio.get_event_loop().run_in_executor(None, _play_sync, path)


def _play_sync(path: Path) -> None:
    system = platform.system()
    try:
        if system == "Darwin":
            subprocess.run(["afplay", str(path)], check=True)
        elif system == "Windows":
            # Windows: PowerShell Media.SoundPlayer (WAV) veya wmplayer (MP3)
            import os
            os.startfile(str(path))  # type: ignore[attr-defined]
        else:
            # Linux: ffplay veya aplay
            for cmd in [["ffplay", "-nodisp", "-autoexit", str(path)],
                        ["aplay", str(path)]]:
                try:
                    subprocess.run(cmd, check=True,
                                   stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
                    return
                except FileNotFoundError:
                    continue
            logger.warning("Ses çalar bulunamadı (ffplay/aplay). Linux'ta 'sudo apt install ffmpeg' çalıştırın.")
    except Exception as ex:
        logger.error(f"Ses çalma hatası: {ex}")
