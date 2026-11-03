#!/usr/bin/env python3
"""Render docs/PHASE3_REPORT.md to a compact PDF (requires reportlab).

This optional documentation helper is independent of the Kotlin/Gradle build.
"""
from html import escape
from pathlib import Path
import re

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.platypus import SimpleDocTemplate, Paragraph, Preformatted, Spacer

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/PHASE3_REPORT.md"
OUTPUT = ROOT / "output/pdf/phase3-report.pdf"


def inline(text):
    # Typography is deliberately portable across stock ReportLab installations.
    text = text.replace("—", "-").replace("–", "-").replace("→", "->")
    text = text.replace("“", '"').replace("”", '"').replace("’", "'").replace("…", "...")
    text = escape(text)
    text = re.sub(r"`([^`]+)`", r'<font name="Courier" size="8.5">\1</font>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"\[([^]]+)\]\([^)]+\)", r"\1", text)
    return text


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    base = getSampleStyleSheet()
    styles = {
        "title": ParagraphStyle("ReportTitle", parent=base["Title"], fontName="Helvetica-Bold",
                                fontSize=17, leading=21, textColor=colors.HexColor("#16324a"),
                                alignment=TA_LEFT, spaceAfter=14),
        "h2": ParagraphStyle("Section", parent=base["Heading2"], fontSize=11.2, leading=14,
                             spaceBefore=10, spaceAfter=5, keepWithNext=True, textColor=colors.HexColor("#16324a")),
        "body": ParagraphStyle("ReportBody", parent=base["BodyText"], fontName="Helvetica",
                               fontSize=9.8, leading=13, spaceAfter=5),
        "code": ParagraphStyle("ReportCode", fontName="Courier", fontSize=8.2, leading=10.8,
                               leftIndent=9, rightIndent=9, borderPadding=7,
                               backColor=colors.HexColor("#f1f4f7"), spaceBefore=3, spaceAfter=7),
    }
    story = []
    paragraph = []
    code = []
    in_code = False

    def flush():
        if paragraph:
            story.append(Paragraph(inline(" ".join(paragraph)), styles["body"]))
            paragraph.clear()

    for line in SOURCE.read_text().splitlines():
        if line.startswith("```"):
            flush()
            if in_code:
                story.append(Preformatted("\n".join(code), styles["code"]))
                code.clear()
            in_code = not in_code
        elif in_code:
            code.append(line.replace("→", "->").replace("—", "-"))
        elif line.startswith("# "):
            flush()
            story.append(Paragraph(inline(line[2:]), styles["title"]))
        elif line.startswith("## "):
            flush()
            story.append(Paragraph(inline(line[3:]), styles["h2"]))
        elif line.startswith("### "):
            flush()
            story.append(Paragraph("<b>" + inline(line[4:]) + "</b>", styles["body"]))
        elif not line.strip():
            flush()
        elif line.startswith("- "):
            flush()
            story.append(Paragraph("&#8226; " + inline(line[2:]), styles["body"]))
        elif line.startswith("---"):
            flush()
            story.append(Spacer(1, 4))
        else:
            paragraph.append(line.strip())
    flush()

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setStrokeColor(colors.HexColor("#cbd5df"))
        canvas.line(45, 38, A4[0] - 45, 38)
        canvas.setFillColor(colors.HexColor("#536575"))
        canvas.setFont("Helvetica", 8)
        canvas.drawString(45, 25, "Project O | Compiler Construction | Innopolis University")
        canvas.drawRightString(A4[0] - 45, 25, str(doc.page))
        canvas.restoreState()

    doc = SimpleDocTemplate(str(OUTPUT), pagesize=A4, rightMargin=45, leftMargin=45,
                            topMargin=35, bottomMargin=45,
                            title="Project O - Phase 3: Semantic Analysis and AST Optimization",
                            author="Project O", pageCompression=1)
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(OUTPUT)


if __name__ == "__main__":
    main()
