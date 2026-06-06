"""
Hafıza ve hassas veri şifreleme.

Fernet (AES-128 CBC + HMAC) kullanır. Anahtar:
1. .env içindeki ``DILARA_ENCRYPTION_KEY`` (varsa)
2. Yoksa OS keyring'den okur (varsa)
3. Yoksa otomatik üretir, keyring'e kaydeder

Bu sayede kullanıcı manuel anahtar yönetmek zorunda değil.
"""

from __future__ import annotations

import base64
import os
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

try:
    import keyring  # type: ignore

    _HAS_KEYRING = True
except Exception:
    _HAS_KEYRING = False


_KEYRING_SERVICE = "dilara-ai-assistant"
_KEYRING_USER = "encryption-key"


def _get_or_create_key(env_key: str = "") -> bytes:
    """Şifreleme anahtarını al veya yarat."""
    if env_key:
        try:
            return _normalize_key(env_key)
        except Exception:
            pass

    if _HAS_KEYRING:
        stored = _keyring_get_with_timeout(timeout=3.0)
        if stored:
            try:
                return _normalize_key(stored)
            except Exception:
                pass

    new_key = Fernet.generate_key()

    if _HAS_KEYRING:
        try:
            keyring.set_password(_KEYRING_SERVICE, _KEYRING_USER, new_key.decode())
        except Exception:
            pass

    return new_key


def _keyring_get_with_timeout(timeout: float = 3.0) -> Optional[str]:
    """Keyring'i ayrı thread'de oku; OS Keychain takılırsa zaman aşımına uğra."""
    import threading

    result: list[Optional[str]] = []

    def _worker() -> None:
        try:
            result.append(keyring.get_password(_KEYRING_SERVICE, _KEYRING_USER))
        except Exception:
            result.append(None)

    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()
    thread.join(timeout=timeout)
    if not result:
        return None
    return result[0]


def _normalize_key(value: str) -> bytes:
    """Anahtar 32 byte base64 değilse uygun forma getirir."""
    raw = value.encode() if isinstance(value, str) else value
    try:
        Fernet(raw)
        return raw
    except Exception:
        # Kullanıcı düz metin verdiyse, padding'le 32 byte yap
        padded = raw.ljust(32, b"0")[:32]
        return base64.urlsafe_b64encode(padded)


class Cipher:
    """Şeffaf encrypt/decrypt sarmalayıcı."""

    def __init__(self, key: Optional[str] = None) -> None:
        env = key or os.environ.get("DILARA_ENCRYPTION_KEY", "")
        self._fernet = Fernet(_get_or_create_key(env))

    def encrypt(self, plaintext: str) -> str:
        if plaintext is None:
            return ""
        return self._fernet.encrypt(plaintext.encode("utf-8")).decode("utf-8")

    def decrypt(self, token: str) -> str:
        if not token:
            return ""
        try:
            return self._fernet.decrypt(token.encode("utf-8")).decode("utf-8")
        except InvalidToken:
            return ""
