"""
Dilara'nın ana uygulama orchestrator'ı.

Burada:
1. Ayarlar yüklenir (.env)
2. Logger ayarlanır
3. PermissionManager + EventBus oluşturulur
4. Tüm servisler (LLM, TTS, STT, hafıza, web, sistem) hazırlanır
5. Kişilik ve Assistant kurulur
6. Wake word + GUI başlatılır
7. Olay döngüsü çalıştırılır

Bu dosya manuel test için CLI moduyla da çalışabilir
(``python -m dilara --cli``) — bu durumda GUI açılmaz, terminal sohbeti.
"""

from __future__ import annotations

import argparse
import asyncio
import signal
import sys
from pathlib import Path

from dilara.core.config import Settings, get_settings
from dilara.core.events import EventBus
from dilara.core.logging import configure_logging, logger
from dilara.core.permissions import Permission, PermissionManager
from dilara.core.security import Cipher
from dilara.modules.assistant import Assistant
from dilara.modules.personality import Personality
from dilara.modules.tools import build_tool_registry
from dilara.services.audio import MicrophoneCapture
from dilara.services.llm import build_llm
from dilara.services.memory.extractor import MemoryExtractor
from dilara.services.memory.short_term import ShortTermMemory
from dilara.services.memory.store import MemoryStore
from dilara.services.research.web import WebResearcher
from dilara.services.stt import build_stt
from dilara.services.syscontrol import SystemController
from dilara.services.tts import build_tts
from dilara.services.vision.screen import ScreenAnalyzer
from dilara.services.wake import WakeWordDetector


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="dilara", description="Dilara AI Asistan")
    p.add_argument("--cli", action="store_true", help="GUI açma, terminalden konuş.")
    p.add_argument("--no-wake", action="store_true", help="Wake word kapalı.")
    p.add_argument("--probe", action="store_true", help="Sadece import'ları doğrula.")
    return p.parse_args()


class DilaraApp:
    """Tüm servisleri bağlayan kompozisyon kökü."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        configure_logging(settings.log_dir, level=settings.log_level)
        logger.info("Dilara başlatılıyor...")

        self.events = EventBus()
        self.permissions = PermissionManager()

        # Servisler — lazy yüklenecekler ama factory'leri burada
        self.cipher = Cipher(settings.encryption_key)
        self.memory = MemoryStore(
            db_path=settings.memory_dir / "dilara.db",
            cipher=self.cipher,
            enable_vector=True,
        )

        self.llm = build_llm(settings)
        self.tts = build_tts(settings)
        self.stt = build_stt(settings)

        self.system = SystemController(self.permissions)
        self.screen = ScreenAnalyzer(self.permissions)
        self.researcher = WebResearcher(
            self.permissions,
            llm=self.llm,
            llm_model=settings.llm_model,
        )

        self.personality = Personality(
            user_name=settings.user_name,
            default_mode=settings.default_mode,
        )

        self.tools = build_tool_registry(
            permissions=self.permissions,
            memory=self.memory,
            researcher=self.researcher,
            system=self.system,
            screen=self.screen,
            output_dir=settings.data_dir / "outputs",
        )

        self.extractor = MemoryExtractor(
            llm=self.llm, store=self.memory, model=settings.llm_model
        )
        self.short_term = ShortTermMemory(max_turns=20)

        self.assistant = Assistant(
            settings=settings,
            permissions=self.permissions,
            events=self.events,
            llm=self.llm,
            tts=self.tts,
            memory=self.memory,
            personality=self.personality,
            tools=self.tools,
            memory_extractor=self.extractor,
            short_term=self.short_term,
        )

        self.mic = MicrophoneCapture()
        self.wake: WakeWordDetector | None = None
        self._listening = False

    # --- Wake word ---
    def setup_wake(self, loop: asyncio.AbstractEventLoop) -> None:
        if not self.settings.wake_enabled:
            return
        self.wake = WakeWordDetector(
            keyword=self.settings.wake_word,
            threshold=self.settings.wake_threshold,
            loop=loop,
        )

    async def start_wake_listening(self) -> None:
        if self.wake and not self.wake.running:
            try:
                self.permissions.ensure(Permission.MICROPHONE)
            except Exception:
                logger.info("Mikrofon izni yok, wake word başlatılmıyor.")
                return
            self.wake.start(on_wake=self._on_wake)

    def stop_wake_listening(self) -> None:
        if self.wake:
            self.wake.stop()

    async def _on_wake(self) -> None:
        self.events.emit("wake.detected")
        await self._listen_and_respond()

    # --- Mikrofon -> STT -> LLM -> TTS ---
    async def _listen_and_respond(self) -> None:
        try:
            self.permissions.ensure(Permission.MICROPHONE)
        except Exception as e:
            logger.warning(f"Mikrofon izni yok: {e}")
            return

        # Aynı anda birden fazla kayıt başlamasını engelle (çift cevap/çift kayıt)
        if self._listening:
            logger.debug("Zaten dinleniyor, yeni kayıt başlatılmadı.")
            return
        self._listening = True

        # Wake dedektörü mikrofonu tutuyorsa serbest bırak
        wake_was_running = self.wake is not None and self.wake.running
        if wake_was_running:
            self.wake.stop()
            await asyncio.sleep(0.15)  # stream'in kapanması için bekle

        try:
            capture = await self.mic.record_until_silence()
        finally:
            self._listening = False
            # Kayıt bitince wake'i tekrar başlat
            if wake_was_running and self.wake is not None:
                self.wake.start(self._on_wake)

        if capture.samples.size == 0:
            self.events.emit("stt.final", text="")
            return

        self.events.emit("stt.partial", text="(işleniyor)")
        result = await self.stt.transcribe(
            capture.samples,
            sample_rate=capture.sample_rate,
            language=self.settings.stt_language,
        )
        text = result.text.strip()
        # stt.final olayı UI tarafından yakalanıp respond tetikler.
        # Burada ayrıca respond çağırmıyoruz; yoksa çift cevap olur.
        self.events.emit("stt.final", text=text)

    # --- Yaşam döngüsü ---
    def shutdown(self) -> None:
        logger.info("Dilara kapatılıyor...")
        self.stop_wake_listening()


# ===========================================================
#                 Çalıştırma modları
# ===========================================================


def run_cli(app: DilaraApp) -> int:
    """Terminal modu — sesli wake word olmadan, yazarak konuş."""

    async def _main():
        print("Dilara CLI moduna girdi. Çıkış için 'exit'.")
        print("Yetki vermek için 'aktif' yaz, kapatmak için 'pasif'.")
        loop = asyncio.get_event_loop()
        while True:
            try:
                user = await loop.run_in_executor(None, lambda: input("> ").strip())
            except (EOFError, KeyboardInterrupt):
                break
            if not user:
                continue
            if user.lower() in {"exit", "quit", "çıkış"}:
                break
            if user.lower() == "aktif":
                app.permissions.activate(grant_all=True)
                print("[Sistem] Yetki verildi.")
                continue
            if user.lower() == "pasif":
                app.permissions.deactivate()
                print("[Sistem] Yetki kapatıldı.")
                continue
            try:
                response = await app.assistant.respond(user, speak=False)
                print(f"Dilara: {response}")
            except Exception as e:
                print(f"[Hata] {e}")
        return 0

    try:
        return asyncio.run(_main())
    finally:
        app.shutdown()


def run_gui(app: DilaraApp) -> int:
    """GUI modu — PySide6 + qasync."""
    try:
        from PySide6.QtWidgets import QApplication
        import qasync  # type: ignore

        from dilara.ui.main_window import MainWindow
    except Exception as e:
        logger.error(f"GUI bağımlılıkları yok: {e}")
        return 1

    qt_app = QApplication.instance() or QApplication(sys.argv)
    loop = qasync.QEventLoop(qt_app)
    asyncio.set_event_loop(loop)

    window = MainWindow(
        permissions=app.permissions,
        personality=app.personality,
        events=app.events,
    )
    window.show()

    app.setup_wake(loop)

    # Yetki değiştiğinde wake word'u aç/kapat
    def on_perm_change(permission, granted):  # noqa: ANN001
        app.events.emit("permission.changed", permission=permission.value, granted=granted)
        if permission == Permission.MICROPHONE:
            if granted:
                asyncio.ensure_future(app.start_wake_listening())
            else:
                app.stop_wake_listening()

    app.permissions.on_change(on_perm_change)

    # Mesaj gönderme
    def on_user_text(text: str) -> None:
        async def _do():
            await app.assistant.respond(text, speak=True)
            window.add_bubble(app.short_term.messages()[-1].content, role="assistant")

        asyncio.ensure_future(_do())

    # Daha doğru: cevap geldiğinde balon ekle
    def on_response(event):  # noqa: ANN001
        window.add_bubble(event.payload.get("text", ""), role="assistant")

    app.events.on("llm.response", on_response)
    window.user_submitted.connect(
        lambda text: asyncio.ensure_future(app.assistant.respond(text, speak=True))
    )

    def on_mic_request(_event):  # noqa: ANN001
        asyncio.ensure_future(app._listen_and_respond())

    app.events.on("mic.requested", on_mic_request)

    try:
        with loop:
            loop.run_forever()
    finally:
        app.shutdown()
    return 0


def run_probe(app: DilaraApp) -> int:
    """Sadece import sağlık kontrolü."""
    print("Probe başarılı — tüm modüller import edildi.")
    print(f"  LLM: {app.llm.name} ({app.settings.llm_model})")
    print(f"  TTS: {app.settings.tts_provider} / {app.settings.tts_voice}")
    print(f"  STT: {app.settings.stt_provider} / {app.settings.stt_model}")
    print(f"  Wake word: {app.settings.wake_word} ({'açık' if app.settings.wake_enabled else 'kapalı'})")
    print(f"  Hafıza DB: {app.memory.db_path}")
    return 0


def run() -> int:
    args = _parse_args()
    settings = get_settings()

    try:
        app = DilaraApp(settings)
    except Exception as e:
        print(f"Başlatma hatası: {e}", file=sys.stderr)
        return 2

    if args.no_wake:
        app.settings.wake_enabled = False  # type: ignore[misc]

    if args.probe:
        return run_probe(app)
    if args.cli:
        return run_cli(app)
    return run_gui(app)
