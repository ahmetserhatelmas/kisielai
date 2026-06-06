"""PDF üretimi (ReportLab)."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from dilara.core.logging import logger


def write_pdf(
    output_path: Path,
    title: str,
    body: str,
    *,
    subtitle: Optional[str] = None,
    author: Optional[str] = None,
) -> Path:
    """Basit ama temiz PDF üretir (Türkçe karakter desteği dahil)."""
    try:
        from reportlab.lib.pagesizes import A4  # type: ignore
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle  # type: ignore
        from reportlab.lib.units import cm  # type: ignore
        from reportlab.platypus import (  # type: ignore
            Paragraph,
            SimpleDocTemplate,
            Spacer,
        )
    except Exception as e:
        logger.error(f"reportlab yüklenemedi: {e}")
        raise

    output_path.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(output_path),
        pagesize=A4,
        leftMargin=2 * cm,
        rightMargin=2 * cm,
        topMargin=2 * cm,
        bottomMargin=2 * cm,
        title=title,
        author=author or "Dilara",
    )

    styles = getSampleStyleSheet()
    styles.add(
        ParagraphStyle(
            name="TR_Title", parent=styles["Title"], fontSize=22, leading=26
        )
    )
    styles.add(
        ParagraphStyle(
            name="TR_H1", parent=styles["Heading1"], fontSize=16, leading=20
        )
    )
    styles.add(
        ParagraphStyle(
            name="TR_Body", parent=styles["BodyText"], fontSize=11, leading=15
        )
    )

    story = []
    if title:
        story.append(Paragraph(title, styles["TR_Title"]))
        story.append(Spacer(1, 0.3 * cm))
    if subtitle:
        story.append(Paragraph(f"<i>{subtitle}</i>", styles["TR_Body"]))
        story.append(Spacer(1, 0.2 * cm))
    if author:
        story.append(Paragraph(f"<i>Hazırlayan: {author}</i>", styles["TR_Body"]))
        story.append(Spacer(1, 0.4 * cm))

    for raw in body.split("\n\n"):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("# "):
            story.append(Paragraph(line[2:].strip(), styles["TR_H1"]))
        elif line.startswith("## "):
            story.append(Paragraph(line[3:].strip(), styles["Heading2"]))
        else:
            safe = line.replace("\n", "<br/>")
            story.append(Paragraph(safe, styles["TR_Body"]))
        story.append(Spacer(1, 0.2 * cm))

    doc.build(story)
    return output_path
