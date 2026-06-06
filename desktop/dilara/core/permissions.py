"""
İzin yönetim sistemi.

Dilara'nın **temel güvenlik garantisi**: kullanıcı manuel olarak yetki
vermeden hiçbir hassas işlem (internet, mikrofon, kamera, sistem
kontrolü, sync) yapılamaz.

Tüm hassas servisler, çağrı yapmadan önce ``ensure(permission)``
kontrolünden geçer. Yetki yoksa ``PermissionDenied`` fırlatır.

Yetkiler oturum başına saklanır (uygulama kapatılınca sıfırlanır).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from threading import RLock
from typing import Callable


class Permission(str, Enum):
    """Mümkün izin kategorileri."""

    INTERNET = "internet"            # API çağrıları, web araştırması
    MICROPHONE = "microphone"        # Mikrofon ve wake word
    CAMERA = "camera"                # Kamera erişimi
    SCREEN = "screen"                # Ekran görüntüsü, ekran analizi
    SYSTEM_CONTROL = "system"        # Uygulama açma, ses ayarı, vb.
    FILE_WRITE = "file_write"        # Dosya oluşturma
    SYNC = "sync"                    # Backend ile senkronizasyon
    MEMORY_WRITE = "memory_write"    # Hafızaya yazma


class PermissionDenied(Exception):
    """İzin verilmemiş bir işlem yapılmaya çalışıldığında fırlatılır."""


@dataclass
class PermissionState:
    """Tek bir iznin anlık durumu."""

    permission: Permission
    granted: bool = False
    reason: str = ""


class PermissionManager:
    """
    Tüm izinleri merkezi olarak yöneten sınıf.

    Singleton kullanım önerilir; ``app.py`` içinde tek instance üretilir.
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._state: dict[Permission, PermissionState] = {
            p: PermissionState(permission=p) for p in Permission
        }
        self._listeners: list[Callable[[Permission, bool], None]] = []
        self._master_active: bool = False

    # --- Master switch ---
    @property
    def master_active(self) -> bool:
        """Genel "Yetki Ver / Aktif Et" durumu."""
        return self._master_active

    def activate(self, *, grant_all: bool = True) -> None:
        """
        Master aktivasyon. Kullanıcı GUI'deki ``Yetki Ver`` butonuna
        bastığında çağrılır.

        ``grant_all=True`` ise temel izinler otomatik açılır
        (internet, mikrofon, hafıza, dosya). Hassas olanlar (kamera,
        ekran, sistem) ayrıca onaylanmalıdır.
        """
        with self._lock:
            self._master_active = True
            if grant_all:
                for p in (
                    Permission.INTERNET,
                    Permission.MICROPHONE,
                    Permission.MEMORY_WRITE,
                    Permission.FILE_WRITE,
                ):
                    self._set(p, True, reason="master_activate")

    def deactivate(self) -> None:
        """Master kapatma — tüm izinleri sıfırlar."""
        with self._lock:
            self._master_active = False
            for p in Permission:
                self._set(p, False, reason="master_deactivate")

    # --- Bireysel izinler ---
    def grant(self, permission: Permission, *, reason: str = "user") -> None:
        with self._lock:
            if not self._master_active:
                raise PermissionDenied(
                    "Master aktivasyon yapılmadan tek tek izin verilemez."
                )
            self._set(permission, True, reason=reason)

    def revoke(self, permission: Permission, *, reason: str = "user") -> None:
        with self._lock:
            self._set(permission, False, reason=reason)

    def is_granted(self, permission: Permission) -> bool:
        with self._lock:
            return self._master_active and self._state[permission].granted

    def ensure(self, permission: Permission) -> None:
        """
        Hassas işlemler bu metodu çağırır. İzin yoksa exception.
        """
        if not self.is_granted(permission):
            raise PermissionDenied(
                f"'{permission.value}' izni verilmemiş. "
                f"Önce uygulamadan yetki ver."
            )

    def snapshot(self) -> dict[str, bool]:
        with self._lock:
            return {
                "master_active": self._master_active,
                **{p.value: self._state[p].granted for p in Permission},
            }

    # --- Olay dinleyicileri ---
    def on_change(self, listener: Callable[[Permission, bool], None]) -> None:
        self._listeners.append(listener)

    # --- Kalıcı durum (opsiyonel) ---
    def save_session(self, path: Path) -> None:
        """Oturum durumunu diske yazar (sadece bilgi amaçlı)."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.snapshot(), indent=2), encoding="utf-8")

    # --- İçsel ---
    def _set(self, permission: Permission, granted: bool, *, reason: str) -> None:
        old = self._state[permission].granted
        self._state[permission].granted = granted
        self._state[permission].reason = reason
        if old != granted:
            for listener in self._listeners:
                try:
                    listener(permission, granted)
                except Exception:
                    pass
