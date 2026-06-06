"""
Cross-platform sistem kontrolü.

Windows / macOS / Linux için ortak bir API sunar:
- Uygulama açma / kapatma
- Ses açma / kısma
- Pano (clipboard) okuma / yazma
- Klasör açma
- Url açma

Banka ve finans uygulamaları kara liste kontrolünden geçer:
``BANK_BLACKLIST`` içindeki herhangi bir isim varsa işlem reddedilir.
"""

from __future__ import annotations

import os
import platform
import subprocess
import webbrowser
from pathlib import Path
from typing import Optional

from dilara.core.logging import logger
from dilara.core.permissions import Permission, PermissionManager


# Banka / finans güvenliği — bu isimleri içeren komutlar engellenir
BANK_BLACKLIST = {
    "akbank", "garanti", "yapikredi", "yapı kredi", "isbank", "iş bankası",
    "ziraat", "halkbank", "vakifbank", "vakıfbank", "denizbank", "qnbfinansbank",
    "qnb finans", "teb", "ing", "albaraka", "kuveytturk", "kuveyt türk",
    "papara", "ininal", "tosla", "fast", "fastpay",
    "binance", "btcturk", "paribu", "coinbase",
    "paypal", "wise", "revolut",
    "internet bankacılığı", "mobil bankacılık", "para transferi", "havale", "eft",
}


class SecurityViolation(Exception):
    pass


def _check_blacklist(target: str) -> None:
    """Banka adı içeriyorsa hata fırlat."""
    lower = target.lower()
    for bad in BANK_BLACKLIST:
        if bad in lower:
            raise SecurityViolation(
                f"Güvenlik politikası: '{bad}' içeren işlemler kapsam dışı."
            )


class SystemController:
    def __init__(self, permissions: PermissionManager) -> None:
        self.permissions = permissions
        self.os = platform.system()  # "Windows" | "Darwin" | "Linux"

    # --- Uygulama ---
    def open_application(self, name: str) -> bool:
        self.permissions.ensure(Permission.SYSTEM_CONTROL)
        _check_blacklist(name)
        try:
            if self.os == "Windows":
                subprocess.Popen(["start", "", name], shell=True)
            elif self.os == "Darwin":
                subprocess.Popen(["open", "-a", name])
            else:
                subprocess.Popen([name])
            logger.info(f"Uygulama açıldı: {name}")
            return True
        except Exception as e:
            logger.error(f"Uygulama açma hatası ({name}): {e}")
            return False

    def open_url(self, url: str) -> bool:
        self.permissions.ensure(Permission.INTERNET)
        _check_blacklist(url)
        try:
            webbrowser.open(url)
            return True
        except Exception as e:
            logger.error(f"URL açma hatası: {e}")
            return False

    def open_folder(self, path: str) -> bool:
        self.permissions.ensure(Permission.SYSTEM_CONTROL)
        p = Path(path).expanduser()
        if not p.exists():
            return False
        try:
            if self.os == "Windows":
                os.startfile(str(p))  # type: ignore[attr-defined]
            elif self.os == "Darwin":
                subprocess.Popen(["open", str(p)])
            else:
                subprocess.Popen(["xdg-open", str(p)])
            return True
        except Exception as e:
            logger.error(f"Klasör açma hatası: {e}")
            return False

    # --- Ses ---
    def set_volume(self, level: int) -> bool:
        """0-100 arası ses seviyesi."""
        self.permissions.ensure(Permission.SYSTEM_CONTROL)
        level = max(0, min(100, int(level)))
        try:
            if self.os == "Windows":
                self._set_volume_windows(level)
            elif self.os == "Darwin":
                subprocess.run(
                    ["osascript", "-e", f"set volume output volume {level}"],
                    check=False,
                )
            else:
                subprocess.run(
                    ["amixer", "-D", "pulse", "sset", "Master", f"{level}%"],
                    check=False,
                )
            return True
        except Exception as e:
            logger.error(f"Ses ayarı hatası: {e}")
            return False

    def _set_volume_windows(self, level: int) -> None:
        try:
            from ctypes import POINTER, cast  # type: ignore
            from comtypes import CLSCTX_ALL  # type: ignore
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume  # type: ignore

            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume = cast(interface, POINTER(IAudioEndpointVolume))
            volume.SetMasterVolumeLevelScalar(level / 100.0, None)
        except Exception as e:
            logger.error(f"Windows ses ayarı: {e}")

    # --- Pano ---
    def clipboard_read(self) -> str:
        self.permissions.ensure(Permission.SYSTEM_CONTROL)
        try:
            if self.os == "Windows":
                proc = subprocess.run(
                    ["powershell", "-command", "Get-Clipboard"],
                    capture_output=True, text=True
                )
                return proc.stdout.rstrip("\n")
            if self.os == "Darwin":
                proc = subprocess.run(
                    ["pbpaste"], capture_output=True, text=True
                )
                return proc.stdout
            proc = subprocess.run(
                ["xclip", "-selection", "clipboard", "-o"],
                capture_output=True, text=True
            )
            return proc.stdout
        except Exception:
            return ""

    def clipboard_write(self, text: str) -> bool:
        self.permissions.ensure(Permission.SYSTEM_CONTROL)
        try:
            if self.os == "Windows":
                subprocess.run(["clip"], input=text, text=True, check=False)
            elif self.os == "Darwin":
                subprocess.run(["pbcopy"], input=text, text=True, check=False)
            else:
                subprocess.run(
                    ["xclip", "-selection", "clipboard"],
                    input=text, text=True, check=False
                )
            return True
        except Exception:
            return False
