"""
LLM tool definitionları.

Dilara'nın LLM'i tool calling ile dış dünyayla konuşur. Burada her
tool'un şeması ve handler'ı tanımlı. Handler'lar PermissionManager
üzerinden korunur.
"""

from __future__ import annotations

import asyncio
import datetime as dt
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

from dilara.core.logging import logger
from dilara.core.permissions import Permission, PermissionManager
from dilara.services.filegen import write_docx, write_markdown, write_pdf, write_text
from dilara.services.memory.store import MemoryStore
from dilara.services.research.web import WebResearcher
from dilara.services.syscontrol import SystemController
from dilara.services.vision.screen import ScreenAnalyzer


ToolHandler = Callable[[dict[str, Any]], Awaitable[str]]


class ToolRegistry:
    """Tool şemaları + handler'ları taşıyan kayıt defteri."""

    def __init__(self) -> None:
        self.specs: list[dict[str, Any]] = []
        self.handlers: dict[str, ToolHandler] = {}

    def register(
        self,
        name: str,
        description: str,
        parameters: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        self.specs.append(
            {"name": name, "description": description, "parameters": parameters}
        )
        self.handlers[name] = handler

    def as_llm_tools(self) -> list:
        from dilara.services.llm.base import Tool

        return [
            Tool(
                name=s["name"],
                description=s["description"],
                parameters=s["parameters"],
            )
            for s in self.specs
        ]

    async def call(self, name: str, args: dict[str, Any]) -> str:
        handler = self.handlers.get(name)
        if not handler:
            return f"Tool '{name}' bulunamadı."
        try:
            return await handler(args)
        except Exception as e:
            logger.error(f"Tool '{name}' hatası: {e}")
            return f"Hata: {e}"


def build_tool_registry(
    *,
    permissions: PermissionManager,
    memory: MemoryStore,
    researcher: WebResearcher,
    system: SystemController,
    screen: ScreenAnalyzer,
    output_dir: Path,
) -> ToolRegistry:
    reg = ToolRegistry()

    # --- Hafıza ---
    async def remember_fn(args: dict[str, Any]) -> str:
        permissions.ensure(Permission.MEMORY_WRITE)
        text = args.get("text", "").strip()
        category = args.get("category", "fact")
        importance = float(args.get("importance", 0.5))
        if not text:
            return "Boş hafıza eklenemez."
        rec = await memory.remember(text, category=category, importance=importance)
        return f"Kaydettim: {rec.text}"

    reg.register(
        "remember",
        "Kullanıcı hakkında uzun vadeli hatırlanması gereken bir bilgiyi kaydet.",
        {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Hatırlanacak bilgi."},
                "category": {
                    "type": "string",
                    "enum": ["preference", "routine", "event", "person", "fact", "note"],
                },
                "importance": {"type": "number", "minimum": 0, "maximum": 1},
            },
            "required": ["text"],
        },
        remember_fn,
    )

    async def recall_fn(args: dict[str, Any]) -> str:
        query = args.get("query", "").strip()
        k = int(args.get("k", 5))
        results = await memory.search(query, k=k)
        if not results:
            return "Hafızada bununla ilgili kayıt yok."
        return "\n".join(f"- [{r.category}] {r.text}" for r in results)

    reg.register(
        "recall",
        "Hafızadan bilgi getir. Soruyla semantik olarak yakın kayıtları bulur.",
        {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "k": {"type": "integer", "default": 5},
            },
            "required": ["query"],
        },
        recall_fn,
    )

    # --- Web ---
    async def web_search_fn(args: dict[str, Any]) -> str:
        query = args.get("query", "").strip()
        if not query:
            return "Arama metni boş."
        report = await researcher.research(query, k=int(args.get("k", 5)))
        sources = "\n".join(f"- {s.title}: {s.url}" for s in report.sources)
        return f"{report.summary}\n\nKaynaklar:\n{sources}"

    reg.register(
        "web_search",
        "İnternette araştırma yap, kaynakları özetle. Kullanıcı izin vermeden çalışmaz.",
        {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "k": {"type": "integer", "default": 5},
            },
            "required": ["query"],
        },
        web_search_fn,
    )

    # --- Dosya üretimi ---
    async def make_docx_fn(args: dict[str, Any]) -> str:
        permissions.ensure(Permission.FILE_WRITE)
        title = args.get("title", "Belge")
        body = args.get("body", "")
        filename = args.get("filename") or _safe_filename(title, ".docx")
        path = output_dir / filename
        write_docx(path, title=title, body=body, author="Dilara")
        return f"Word dosyası hazırlandı: {path}"

    reg.register(
        "make_docx",
        "Word (.docx) dosyası üret. body markdown benzeri biçim kabul eder.",
        {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "body": {"type": "string"},
                "filename": {"type": "string"},
            },
            "required": ["title", "body"],
        },
        make_docx_fn,
    )

    async def make_pdf_fn(args: dict[str, Any]) -> str:
        permissions.ensure(Permission.FILE_WRITE)
        title = args.get("title", "Belge")
        body = args.get("body", "")
        filename = args.get("filename") or _safe_filename(title, ".pdf")
        path = output_dir / filename
        write_pdf(path, title=title, body=body, author="Dilara")
        return f"PDF hazırlandı: {path}"

    reg.register(
        "make_pdf",
        "PDF dosyası üret.",
        {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "body": {"type": "string"},
                "filename": {"type": "string"},
            },
            "required": ["title", "body"],
        },
        make_pdf_fn,
    )

    async def make_text_fn(args: dict[str, Any]) -> str:
        permissions.ensure(Permission.FILE_WRITE)
        filename = args.get("filename") or _safe_filename("not", ".txt")
        path = output_dir / filename
        write_text(path, args.get("content", ""))
        return f"Dosya kaydedildi: {path}"

    reg.register(
        "make_text",
        "Düz metin dosyası kaydet.",
        {
            "type": "object",
            "properties": {
                "filename": {"type": "string"},
                "content": {"type": "string"},
            },
            "required": ["content"],
        },
        make_text_fn,
    )

    # --- Sistem ---
    async def open_app_fn(args: dict[str, Any]) -> str:
        ok = system.open_application(args.get("name", ""))
        return "Açtım." if ok else "Açamadım."

    reg.register(
        "open_application",
        "Bilgisayarda bir uygulama aç. Banka uygulamaları engellenir.",
        {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
        open_app_fn,
    )

    async def open_url_fn(args: dict[str, Any]) -> str:
        ok = system.open_url(args.get("url", ""))
        return "Tarayıcıda açtım." if ok else "Açamadım."

    reg.register(
        "open_url",
        "Tarayıcıda URL aç.",
        {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
        open_url_fn,
    )

    async def set_volume_fn(args: dict[str, Any]) -> str:
        ok = system.set_volume(int(args.get("level", 50)))
        return "Ses ayarlandı." if ok else "Ayarlayamadım."

    reg.register(
        "set_volume",
        "Sistem ses seviyesini ayarla (0-100).",
        {
            "type": "object",
            "properties": {"level": {"type": "integer"}},
            "required": ["level"],
        },
        set_volume_fn,
    )

    # --- Görsel ---
    async def screenshot_fn(args: dict[str, Any]) -> str:
        permissions.ensure(Permission.SCREEN)
        path = output_dir / f"screen_{int(dt.datetime.now().timestamp())}.png"
        result = screen.capture(path)
        return f"Ekran görüntüsü alındı: {result}" if result else "Yakalanamadı."

    reg.register(
        "take_screenshot",
        "Ekran görüntüsü al ve kaydet.",
        {"type": "object", "properties": {}},
        screenshot_fn,
    )

    # --- Zaman ---
    async def now_fn(_: dict[str, Any]) -> str:
        return dt.datetime.now().strftime("%A, %d %B %Y %H:%M")

    reg.register(
        "current_time",
        "Şu anki tarih ve saati ver.",
        {"type": "object", "properties": {}},
        now_fn,
    )

    return reg


def _safe_filename(name: str, ext: str) -> str:
    safe = "".join(c if c.isalnum() or c in "-_ " else "_" for c in name).strip()
    safe = safe.replace(" ", "_")[:60]
    if not safe:
        safe = "dosya"
    return f"{safe}_{int(dt.datetime.now().timestamp())}{ext}"
