"""Ekran görüntüsü alma (mss)."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from dilara.core.logging import logger
from dilara.core.permissions import Permission, PermissionManager


class ScreenAnalyzer:
    def __init__(self, permissions: PermissionManager) -> None:
        self.permissions = permissions

    def capture(
        self,
        output_path: Path,
        monitor: int = 0,
    ) -> Optional[Path]:
        """Ekranı yakala. ``monitor=0`` tüm ekranlar."""
        self.permissions.ensure(Permission.SCREEN)
        try:
            import mss  # type: ignore
            import mss.tools  # type: ignore
        except Exception as e:
            logger.error(f"mss yüklenemedi: {e}")
            return None

        output_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with mss.mss() as sct:
                target = sct.monitors[monitor] if monitor < len(sct.monitors) else sct.monitors[0]
                img = sct.grab(target)
                mss.tools.to_png(img.rgb, img.size, output=str(output_path))
            return output_path
        except Exception as e:
            logger.error(f"Ekran yakalama hatası: {e}")
            return None
