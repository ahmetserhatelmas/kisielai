"""Düz metin / Markdown yazıcı."""

from __future__ import annotations

from pathlib import Path


def write_text(output_path: Path, content: str) -> Path:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(content, encoding="utf-8")
    return output_path


def write_markdown(output_path: Path, title: str, body: str) -> Path:
    content = f"# {title}\n\n{body}\n" if title else body
    return write_text(output_path, content)
