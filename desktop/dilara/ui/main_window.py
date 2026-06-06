"""
Dilara'nın ana penceresi.

Üç ana bölüm:
1. Üstte: Aktivasyon butonu + durum göstergeleri
2. Ortada: Sohbet alanı
3. Altta: Yazılı/sesli giriş alanı

Tasarım koyu tema, modern, sade.
"""

from __future__ import annotations

import asyncio
from datetime import datetime
from pathlib import Path
from typing import Optional

from PySide6.QtCore import Qt, Signal, QTimer
from PySide6.QtGui import QFont, QIcon, QColor, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QSpacerItem,
    QStatusBar,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from dilara.core.events import EventBus
from dilara.core.permissions import Permission, PermissionManager
from dilara.modules.personality import Personality


class ChatBubble(QFrame):
    """Tek bir konuşma balonu."""

    def __init__(self, text: str, role: str = "assistant") -> None:
        super().__init__()
        self.setObjectName(f"bubble_{role}")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(14, 10, 14, 10)

        time_label = QLabel(datetime.now().strftime("%H:%M"))
        time_label.setObjectName("bubble_time")
        font_small = QFont()
        font_small.setPointSize(8)
        time_label.setFont(font_small)

        text_label = QLabel(text)
        text_label.setWordWrap(True)
        text_label.setTextInteractionFlags(Qt.TextSelectableByMouse)

        layout.addWidget(time_label)
        layout.addWidget(text_label)


class MainWindow(QMainWindow):
    user_submitted = Signal(str)

    def __init__(
        self,
        permissions: PermissionManager,
        personality: Personality,
        events: EventBus,
    ) -> None:
        super().__init__()
        self.permissions = permissions
        self.personality = personality
        self.events = events
        self.setWindowTitle("Dilara — Kişisel AI Asistan")
        self.resize(900, 720)

        self._build_ui()
        self._wire_events()
        self._apply_theme()

        self._refresh_status()

    # --- UI ---
    def _build_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(20, 20, 20, 20)
        root.setSpacing(14)

        # --- Header ---
        header = QHBoxLayout()
        header.setSpacing(12)

        title = QLabel("Dilara")
        title.setObjectName("title")
        title_font = QFont()
        title_font.setPointSize(22)
        title_font.setBold(True)
        title.setFont(title_font)

        self.subtitle = QLabel("Kişisel AI Asistan")
        self.subtitle.setObjectName("subtitle")

        title_box = QVBoxLayout()
        title_box.addWidget(title)
        title_box.addWidget(self.subtitle)
        header.addLayout(title_box)
        header.addStretch()

        self.activate_btn = QPushButton("Yetki Ver / Aktif Et")
        self.activate_btn.setObjectName("activate")
        self.activate_btn.setMinimumHeight(46)
        self.activate_btn.setMinimumWidth(180)
        self.activate_btn.clicked.connect(self._on_activate_clicked)
        header.addWidget(self.activate_btn)

        self.mode_btn = QPushButton("Normal Mod")
        self.mode_btn.setObjectName("mode")
        self.mode_btn.setMinimumHeight(46)
        self.mode_btn.clicked.connect(self._on_mode_clicked)
        header.addWidget(self.mode_btn)

        root.addLayout(header)

        # --- Status row ---
        self.status_row = QLabel("Durum: pasif. Aktif etmek için yetki ver.")
        self.status_row.setObjectName("status_row")
        root.addWidget(self.status_row)

        # --- Chat area ---
        self.chat_scroll = QScrollArea()
        self.chat_scroll.setWidgetResizable(True)
        self.chat_scroll.setObjectName("chat_scroll")
        self.chat_container = QWidget()
        self.chat_layout = QVBoxLayout(self.chat_container)
        self.chat_layout.setContentsMargins(8, 8, 8, 8)
        self.chat_layout.setSpacing(8)
        self.chat_layout.addStretch()
        self.chat_scroll.setWidget(self.chat_container)
        root.addWidget(self.chat_scroll, stretch=1)

        # --- Input ---
        input_row = QHBoxLayout()
        self.input = QLineEdit()
        self.input.setPlaceholderText(
            "Bir şey yaz veya 'Selam Dilara' diyerek konuş..."
        )
        self.input.setMinimumHeight(46)
        self.input.returnPressed.connect(self._on_send)
        input_row.addWidget(self.input)

        self.send_btn = QPushButton("Gönder")
        self.send_btn.setObjectName("send")
        self.send_btn.setMinimumHeight(46)
        self.send_btn.setMinimumWidth(100)
        self.send_btn.clicked.connect(self._on_send)
        input_row.addWidget(self.send_btn)

        self.mic_btn = QPushButton("🎤")
        self.mic_btn.setObjectName("mic")
        self.mic_btn.setMinimumHeight(46)
        self.mic_btn.setMinimumWidth(60)
        self.mic_btn.clicked.connect(self._on_mic_clicked)
        input_row.addWidget(self.mic_btn)

        root.addLayout(input_row)

        # --- Status bar ---
        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Hazır.")

        self.add_bubble(
            "Selam! Ben Dilara. Henüz yetki vermedin, dolayısıyla pasif moddayım. "
            "Üstteki butona basarsan aktif olurum.",
            role="assistant",
        )

    def _wire_events(self) -> None:
        self.events.on("llm.thinking", lambda e: self.statusBar().showMessage("Düşünüyorum..."))
        self.events.on("llm.response", lambda e: self.statusBar().showMessage("Hazır."))
        self.events.on("tts.start", lambda e: self.statusBar().showMessage("Konuşuyorum..."))
        self.events.on("tts.end", lambda e: self.statusBar().showMessage("Hazır."))
        self.events.on(
            "wake.detected",
            lambda e: self.statusBar().showMessage("Wake word algılandı, dinliyorum..."),
        )
        self.events.on(
            "stt.final",
            lambda e: self._on_stt_final(e.payload.get("text", "")),
        )
        self.events.on(
            "mode.changed",
            lambda e: self._on_mode_changed_external(e.payload.get("mode")),
        )
        self.events.on(
            "permission.changed",
            lambda e: self._refresh_status(),
        )

    # --- Tema ---
    def _apply_theme(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow { background-color: #0f1115; color: #e6e8ec; }
            QLabel { color: #e6e8ec; }
            QLabel#title { color: #ffffff; }
            QLabel#subtitle { color: #8a92a4; }
            QLabel#status_row { color: #9aa3b6; padding: 6px 10px;
                background-color: #161922; border-radius: 8px; }

            QPushButton {
                background-color: #1d212c; color: #e6e8ec;
                border: 1px solid #2a2f3d; border-radius: 12px;
                padding: 8px 16px; font-weight: 600;
            }
            QPushButton:hover { background-color: #252a37; }
            QPushButton#activate {
                background-color: #1f6feb; color: white;
                border: 1px solid #1f6feb;
            }
            QPushButton#activate:checked, QPushButton#activate[active="true"] {
                background-color: #2ea043; border-color: #2ea043;
            }
            QPushButton#mode { background-color: #2a2f3d; }
            QPushButton#send { background-color: #1f6feb; color: white; border: none; }
            QPushButton#send:hover { background-color: #388bfd; }
            QPushButton#mic { background-color: #6f42c1; color: white; border: none; }

            QLineEdit {
                background-color: #161922; color: #e6e8ec;
                border: 1px solid #2a2f3d; border-radius: 12px;
                padding: 8px 14px; font-size: 14px;
            }
            QLineEdit:focus { border-color: #1f6feb; }

            QScrollArea#chat_scroll, QScrollArea#chat_scroll > QWidget > QWidget {
                background-color: #0f1115; border: none;
            }

            QFrame#bubble_assistant {
                background-color: #1a1e2a;
                border: 1px solid #232838;
                border-radius: 14px;
                color: #e6e8ec;
            }
            QFrame#bubble_user {
                background-color: #1f6feb;
                border-radius: 14px;
                color: white;
            }
            QLabel#bubble_time { color: #6b7280; }

            QStatusBar { background-color: #0f1115; color: #9aa3b6; }
            """
        )

    # --- Sohbet ---
    def add_bubble(self, text: str, role: str = "assistant") -> None:
        bubble = ChatBubble(text, role=role)
        bubble.setMaximumWidth(int(self.width() * 0.78))

        wrapper = QHBoxLayout()
        wrapper.setContentsMargins(0, 0, 0, 0)
        if role == "user":
            wrapper.addStretch()
            wrapper.addWidget(bubble)
        else:
            wrapper.addWidget(bubble)
            wrapper.addStretch()

        container = QWidget()
        container.setLayout(wrapper)

        self.chat_layout.insertWidget(self.chat_layout.count() - 1, container)
        QTimer.singleShot(50, self._scroll_to_bottom)

    def _scroll_to_bottom(self) -> None:
        bar = self.chat_scroll.verticalScrollBar()
        bar.setValue(bar.maximum())

    # --- Olaylar ---
    def _on_send(self) -> None:
        text = self.input.text().strip()
        if not text:
            return
        self.input.clear()
        self.add_bubble(text, role="user")
        self.user_submitted.emit(text)

    def _on_stt_final(self, text: str) -> None:
        if not text.strip():
            return
        self.add_bubble(text, role="user")
        self.user_submitted.emit(text)

    def _on_activate_clicked(self) -> None:
        if self.permissions.master_active:
            self.permissions.deactivate()
        else:
            self.permissions.activate(grant_all=True)
        self._refresh_status()

    def _on_mode_clicked(self) -> None:
        new_mode = "serious" if self.personality.mode == "normal" else "normal"
        self.personality.set_mode(new_mode)
        self.events.emit("mode.changed", mode=new_mode)
        self._refresh_status()

    def _on_mode_changed_external(self, mode: Optional[str]) -> None:
        if mode in ("normal", "serious"):
            self.personality.set_mode(mode)
            self._refresh_status()

    def _on_mic_clicked(self) -> None:
        self.events.emit("mic.requested")
        self.statusBar().showMessage("Dinliyorum, konuş...")

    # --- Refresh ---
    def _refresh_status(self) -> None:
        active = self.permissions.master_active
        self.activate_btn.setText("Aktif (kapatmak için bas)" if active else "Yetki Ver / Aktif Et")
        self.activate_btn.setProperty("active", "true" if active else "false")
        self.activate_btn.style().unpolish(self.activate_btn)
        self.activate_btn.style().polish(self.activate_btn)

        mode = self.personality.mode
        self.mode_btn.setText("Ciddi Mod" if mode == "serious" else "Normal Mod")

        snap = self.permissions.snapshot()
        if active:
            granted = [k for k, v in snap.items() if k != "master_active" and v]
            self.status_row.setText(
                f"Aktif. İzinli: {', '.join(granted) if granted else 'yok'}. "
                f"Mod: {mode}"
            )
        else:
            self.status_row.setText(
                "Pasif. İnternet/mikrofon/sistem kontrolü kapalı. "
                "Aktif etmek için sağ üstteki butona bas."
            )
