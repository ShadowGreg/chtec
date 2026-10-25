"""Извлечение текста из файлов: .doc, .docx, .md, .bin, .txt.

Результат — список абзацев Block(kind, text), где kind = "heading" или "para".
"""
from __future__ import annotations

import re
import struct
import zipfile
from dataclasses import dataclass
from pathlib import Path

OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
ZIP_MAGIC = b"PK\x03\x04"
RTF_MAGIC = b"{\\rtf"


@dataclass
class Block:
    kind: str  # "heading" | "para"
    text: str


def load_blocks(path: str | Path) -> list[Block]:
    path = Path(path)
    data = path.read_bytes()
    ext = path.suffix.lower()

    # Сначала смотрим на содержимое: .bin и переименованные файлы часто оказываются Word-документами.
    if data.startswith(OLE_MAGIC):
        return plain_to_blocks(_read_doc(data))
    if data.startswith(ZIP_MAGIC) and _is_docx(data):
        return _read_docx(path)
    if data.startswith(RTF_MAGIC):
        return plain_to_blocks(_rtf_to_text(decode_bytes(data)))

    text = decode_bytes(data)
    if ext in (".md", ".markdown") or (ext == ".bin" and _looks_like_markdown(text)):
        return markdown_to_blocks(text)
    return plain_to_blocks(text)


# ---------------------------------------------------------------- кодировки

def decode_bytes(data: bytes) -> str:
    """Определяет кодировку (UTF-8/16, CP1251, KOI8-R) и, если файл бинарный, вытаскивает из него текст."""
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8", "replace")
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16", "replace")

    for enc in ("utf-16-le", "utf-16-be"):
        if len(data) % 2 == 0 and _plain_ratio(data[:200_000].decode(enc, "replace")) > 0.9:
            return data.decode(enc, "replace")

    try:
        text = data.decode("utf-8")
        if _printable_ratio(text) > 0.9:
            return text
    except UnicodeDecodeError:
        pass

    best = max(("cp1251", "koi8-r", "utf-8"), key=lambda e: _russian_score(data.decode(e, "replace")))
    text = data.decode(best, "replace")
    controls = sum(1 for b in data[:200_000] if b < 9 or 13 < b < 32 and b != 27)
    if controls > len(data[:200_000]) * 0.01:
        # Бинарный файл: текст может лежать внутри и в 8-битной кодировке, и в UTF-16.
        wide = "\n".join(_extract_strings(data[k:].decode("utf-16-le", "replace")) for k in (0, 1))
        narrow = max((_extract_strings(data.decode(e, "replace")) for e in ("cp1251", "koi8-r", "utf-8")),
                     key=_russian_score)
        text = "\n".join(t for t in (narrow, wide) if t)
    return text


def _russian_score(text: str) -> float:
    letters = [c for c in text.lower() if "а" <= c <= "я" or c == "ё"]
    if not letters:
        return 0.0
    # В KOI8-R, прочитанном как CP1251 (и наоборот), регистр «переворачивается» — заглавных становится много.
    upper = sum(1 for c in text if "А" <= c <= "Я")
    common = sum(1 for c in letters if c in "оеаинтср")
    return common / len(letters) - upper / max(len(letters), 1)


def _printable_ratio(text: str) -> float:
    ok = sum(1 for c in text if c.isprintable() or c in "\n\r\t")
    return ok / max(len(text), 1)


def _plain_ratio(text: str) -> float:
    """Доля символов, обычных для русского/английского текста (буквы, цифры, пунктуация)."""
    ok = sum(1 for c in text if c < "\u0250" and (c.isprintable() or c in "\n\r\t")
             or "\u0400" <= c <= "\u04ff" or "\u2010" <= c <= "\u2026" or c in "«»№")
    return ok / max(len(text), 1)


_GOOD_WORD = re.compile(r"[А-ЯЁ]?[а-яё]+|[А-ЯЁ]+|[A-Z]?[a-z]+|[A-Z]+|\d+")


def _looks_like_words(run: str) -> bool:
    """Отличает настоящий текст от случайных байтов: слова из одного алфавита с нормальным регистром."""
    tokens = re.findall(r"[^\s,.!?;:()«»\"'…—–-]+", run)
    good = [t for t in tokens if _GOOD_WORD.fullmatch(t) and len(t) <= 25]
    return len(good) >= 2 and len(good) >= len(tokens) * 0.8


def _extract_strings(text: str, min_len: int = 6) -> str:
    """Аналог утилиты strings: оставляет только осмысленные куски текста из бинарных данных."""
    # Берём только участки из обычных букв, цифр и пунктуации — без «мусорных» символов по краям.
    runs = re.findall(r"[A-Za-zА-Яа-яЁё][0-9A-Za-zА-Яа-яЁё \t\n\r,.!?;:()«»\"'…—–-]{%d,}[0-9A-Za-zА-Яа-яЁё.!?…»)\"]" % min_len, text)
    runs = [re.sub(r"([.!?…])\S{1,3}$", r"\1", r.strip()) for r in runs if _looks_like_words(r)]
    cyr = [r for r in runs if re.search(r"[А-Яа-яЁё]{3,}", r)]
    return "\n".join(cyr or runs)


# ---------------------------------------------------------------- Word

def _is_docx(data: bytes) -> bool:
    import io
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            return "word/document.xml" in z.namelist()
    except zipfile.BadZipFile:
        return False


def _read_docx(path: Path) -> list[Block]:
    import docx
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    document = docx.Document(str(path))
    blocks: list[Block] = []
    # Абзацы и таблицы в порядке документа; строка таблицы читается одной фразой.
    for child in document.element.body.iterchildren():
        if child.tag.endswith("}tbl"):
            for row in Table(child, document).rows:
                cells = [c.text.strip() for c in row.cells if c.text.strip()]
                if cells:
                    blocks.append(Block("para", ". ".join(cells)))
            continue
        if not child.tag.endswith("}p"):
            continue
        p = Paragraph(child, document)
        text = p.text.strip()
        if not text:
            continue
        style = (p.style.name or "").lower() if p.style is not None else ""
        kind = "heading" if style.startswith(("heading", "заголовок", "title")) or _looks_like_heading(text) else "para"
        blocks.append(Block(kind, text))
    return blocks


def _read_doc(data: bytes) -> str:
    """Читает текст из старого бинарного Word 97–2003 (.doc) по таблице фрагментов (piece table)."""
    import io
    import olefile

    ole = olefile.OleFileIO(io.BytesIO(data))
    word = ole.openstream("WordDocument").read()
    flags = struct.unpack_from("<H", word, 0x0A)[0]
    table_name = "1Table" if flags & 0x0200 else "0Table"
    if not ole.exists(table_name):
        raise ValueError("Повреждённый .doc: нет потока таблицы")
    table = ole.openstream(table_name).read()

    fc_clx, lcb_clx = struct.unpack_from("<II", word, 0x01A2)
    clx = table[fc_clx:fc_clx + lcb_clx]

    pos = 0
    while pos < len(clx) and clx[pos] == 0x01:  # Prc — пропускаем форматирование
        pos += 3 + struct.unpack_from("<H", clx, pos + 1)[0]
    if pos >= len(clx) or clx[pos] != 0x02:
        raise ValueError("Не удалось разобрать .doc (нет таблицы фрагментов)")
    lcb = struct.unpack_from("<I", clx, pos + 1)[0]
    plc = clx[pos + 5:pos + 5 + lcb]
    n = (lcb - 4) // 12
    cps = struct.unpack_from("<%dI" % (n + 1), plc, 0)

    parts = []
    for i in range(n):
        fc = struct.unpack_from("<I", plc, 4 * (n + 1) + 8 * i + 2)[0]
        count = cps[i + 1] - cps[i]
        if fc & 0x40000000:
            start = (fc & ~0x40000000) // 2
            parts.append(word[start:start + count].decode("cp1252", "replace"))
        else:
            parts.append(word[fc:fc + 2 * count].decode("utf-16-le", "replace"))
    text = "".join(parts)

    # Поля Word: \x13 код \x14 результат \x15 — оставляем только результат.
    text = re.sub(r"\x13[^\x13\x14\x15]*\x14", "", text)
    text = re.sub(r"\x13[^\x13\x14\x15]*\x15", "", text)
    text = text.replace("\x15", "")
    text = text.replace("\r", "\n").replace("\x0b", "\n").replace("\x0c", "\n").replace("\x07", "\n")
    return re.sub(r"[\x00-\x08\x0e-\x1f]", "", text)


def _rtf_to_text(rtf: str) -> str:
    def hex_char(m: re.Match) -> str:
        return bytes([int(m.group(1), 16)]).decode("cp1251", "replace")

    text = re.sub(r"\\'([0-9a-fA-F]{2})", hex_char, rtf)
    text = re.sub(r"\\u(-?\d+)\??", lambda m: chr(int(m.group(1)) % 65536), text)
    text = re.sub(r"\\par[d]?\b", "\n", text)
    text = re.sub(r"\{\\\*[^{}]*\}", "", text)
    text = re.sub(r"\\[a-zA-Z]+-?\d* ?", "", text)
    return text.replace("{", "").replace("}", "")


# ---------------------------------------------------------------- Markdown

def _looks_like_markdown(text: str) -> bool:
    return bool(re.search(r"^(#{1,6} |```)|\]\(|\*\*\S", text, re.M))


def markdown_to_blocks(md: str) -> list[Block]:
    md = re.sub(r"```.*?```", "", md, flags=re.S)  # блоки кода не читаем
    md = re.sub(r"<!--.*?-->", "", md, flags=re.S)
    blocks: list[Block] = []
    para: list[str] = []

    def flush() -> None:
        if para:
            blocks.append(Block("para", " ".join(para)))
            para.clear()

    for raw in md.splitlines():
        line = raw.strip()
        if not line or re.fullmatch(r"[-*_=]{3,}", line) or re.fullmatch(r"\|?[\s:|-]+\|?", line):
            flush()
            continue
        m = re.match(r"(#{1,6})\s+(.*?)\s*#*$", line)
        if m:
            flush()
            blocks.append(Block("heading", _md_inline(m.group(2))))
            continue
        if line.startswith("- ") and re.search(r"[,!?…]\s[-—–]\s[а-яё]", line):
            flush()  # «- Привет, - сказал он.» — это реплика диалога, а не список
            blocks.append(Block("para", "— " + _md_inline(line[2:])))
            continue
        if re.match(r"([-*+]|\d+[.)])\s+", line):  # пункт списка — отдельный абзац
            flush()
            item = _md_inline(re.sub(r"^([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?", "", line))
            blocks.append(Block("para", item))
            continue
        if line.startswith("|"):
            flush()
            cells = [_md_inline(c) for c in line.strip("|").split("|") if c.strip()]
            blocks.append(Block("para", ". ".join(cells)))
            continue
        para.append(_md_inline(re.sub(r"^>\s?", "", line)))
    flush()
    return [b for b in blocks if b.text.strip()]


def _md_inline(s: str) -> str:
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", s)           # картинки
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)        # ссылки → текст
    s = re.sub(r"`([^`]*)`", r"\1", s)
    s = re.sub(r"(\*\*|__|\*|_|~~)(?=\S)(.+?)(?<=\S)\1", r"\2", s)
    s = re.sub(r"<[^>]+>", "", s)
    return s.strip()


# ---------------------------------------------------------------- общее

def _looks_like_heading(line: str) -> bool:
    return (len(line) < 70 and not re.search(r"[.,;:!?…»\"]$", line)
            and not line.startswith(("—", "–", "-")))


def plain_to_blocks(text: str) -> list[Block]:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [l.strip() for l in text.split("\n")]
    blocks: list[Block] = []
    buf: list[str] = []

    def flush() -> None:
        if buf:
            blocks.append(Block("para", " ".join(buf)))
            buf.clear()

    for i, line in enumerate(lines):
        if not line:
            flush()
            continue
        # Короткая строка без точки в конце, окружённая пустыми строками, — скорее заголовок.
        next_empty = i + 1 >= len(lines) or not lines[i + 1]
        if not buf and next_empty and _looks_like_heading(line):
            flush()
            blocks.append(Block("heading", line))
            continue
        # Новая строка, начинающаяся с тире или отступа, — новая реплика/абзац.
        if line.startswith(("—", "–", "- ")):
            flush()
        buf.append(line)
        # Строка закончилась концом предложения — считаем это концом абзаца
        # (в .txt абзац часто записан одной строкой без пустых строк между ними).
        if re.search(r"[.!?…][»\"]?$", line):
            flush()
    flush()
    return blocks
