"""Подготовка текста к чтению: нормализация, разбиение на предложения,
определение интонации по знакам препинания и разметка диалогов.

На выходе — «партитура» для чтеца: список Segment с голосом, интонацией и паузой после.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .extract import Block

# Интонация -> (скорость %, высота Гц, громкость %) поверх общих настроек.
PROSODY = {
    "statement":        (0,   0,   0),
    "question":         (0,  +6,   0),
    "exclaim":          (+6, +10, +10),
    "question_exclaim": (+6, +12, +10),
    "ellipsis":         (-12, -4,  -5),
    "heading":          (-10, -2,  +5),
}

# Пауза после фрагмента, мс.
PAUSE_AFTER = {
    "statement": 350,
    "question": 450,
    "exclaim": 450,
    "question_exclaim": 500,
    "ellipsis": 800,
    "heading": 900,
}
PARAGRAPH_PAUSE = 450   # добавляется в конце абзаца
ROLE_SWITCH_PAUSE = 150  # между репликой персонажа и словами автора

MAX_CHUNK = 1500


@dataclass
class Segment:
    text: str
    role: str        # "narrator" | "character"
    style: str       # ключ из PROSODY
    pause_after: int  # мс


# ---------------------------------------------------------------- нормализация

def normalize(text: str) -> str:
    text = text.replace(" ", " ").replace("­", "")
    text = re.sub(r"https?://\S+|www\.\S+", "", text)
    text = re.sub(r"\S+@\S+\.\w+", "", text)
    text = re.sub(r"\.{3,}|…+", "…", text)
    text = re.sub(r"([!?])\.{2}", r"\1…", text)          # «!..» и «?..» — с недосказанностью
    text = re.sub(r"(?<=\s)[-–](?=\s)", "—", text)        # дефис между словами — это тире
    text = re.sub(r"^[-–](?=\s)", "—", text)
    text = text.replace("№", "номер ").replace("&", " и ").replace("§", "параграф ")
    text = re.sub(r"[*#_~|<>\[\]{}^\\]", " ", text)
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\s+([,.!?…:;»)])", r"\1", text)
    text = re.sub(r"([«(])\s+", r"\1", text)
    return text.strip()


# ---------------------------------------------------------------- предложения

_ABBR = re.compile(
    r"(?:\b(?:т\.\s?е|т\.\s?д|т\.\s?п|т\.\s?к|и\.\s?о|т|е|д|п|г|гг|в|вв|см|ср|др|пр|им|ул|стр|рис|тыс|млн|млрд"
    r"|руб|коп|акад|проф|доц|ст|гл|изд|напр|прим|ред|англ|лат|франц|нем|р|н\.\s?э|с|л|кв|обл|пос)"
    r"|\b[А-ЯЁA-Z])\.$",
    re.I,
)
_SENT = re.compile(r"[^.!?…]*(?:[.!?…]+[»\")]*|$)")


def split_sentences(text: str) -> list[str]:
    raw = [m.group(0).strip() for m in _SENT.finditer(text) if m.group(0).strip()]
    out: list[str] = []
    for s in raw:
        if out:
            prev = out[-1]
            starts_lower = bool(re.match(r"[«(\"]?[а-яёa-z0-9,;:]", s))
            if starts_lower or (_ABBR.search(prev) and not prev.endswith(("!", "?", "…"))):
                out[-1] = prev + " " + s
                continue
        out.append(s)
    return out


def sentence_style(sentence: str) -> str:
    tail = re.sub(r"[»\")\s]+$", "", sentence)[-3:]
    if "?" in tail and "!" in tail:
        return "question_exclaim"
    if tail.endswith("…") and not re.search(r"[!?]…$", tail):
        return "ellipsis"
    if "?" in tail:
        return "question"
    if "!" in tail:
        return "exclaim"
    return "statement"


# ---------------------------------------------------------------- диалоги

_DIALOG_START = re.compile(r"^[—–]\s*")
_DASH = re.compile(r"\s+[—–]\s+")


def split_dialog(paragraph: str) -> list[tuple[str, str]]:
    """«— Привет, — сказал он. — Как дела?» -> [(character, Привет.), (narrator, сказал он.), (character, Как дела?)]."""
    if not _DIALOG_START.match(paragraph):
        return [("narrator", paragraph)]
    body = _DIALOG_START.sub("", paragraph)
    parts = _DASH.split(body)

    result: list[list[str]] = [["character", parts[0]]]
    for part in parts[1:]:
        role, prev = result[-1]
        if role == "character" and re.search(r"[,!?…]$", prev) and re.match(r"[а-яё]", part):
            result.append(["narrator", part])
        elif role == "narrator" and re.search(r"[.,:]$", prev):
            result.append(["character", part])
        else:
            result[-1][1] = prev + " — " + part

    cleaned: list[tuple[str, str]] = []
    for i, (role, text) in enumerate(result):
        text = text.strip()
        # Запятая перед словами автора в устной речи звучит как конец фразы.
        if text.endswith(",") and i + 1 < len(result):
            text = text[:-1] + "."
        if text:
            cleaned.append((role, text))
    return cleaned


# ---------------------------------------------------------------- сборка партитуры

def build_score(blocks: list[Block], expressive: bool = True) -> list[Segment]:
    segments: list[Segment] = []
    for block in blocks:
        text = normalize(block.text)
        if not text:
            continue
        if block.kind == "heading":
            if not re.search(r"[.!?…]$", text):
                text += "."
            segments.append(Segment(text, "narrator", "heading" if expressive else "statement", PAUSE_AFTER["heading"]))
            continue

        para_start = len(segments)
        for role, part in split_dialog(text):
            role_start = len(segments)
            for sent in split_sentences(part):
                style = sentence_style(sent) if expressive else "statement"
                last = segments[-1] if len(segments) > role_start else None
                # Подряд идущие предложения с одной интонацией читаем одним куском —
                # так голос сам связывает их естественной мелодикой.
                if last and last.style == style and len(last.text) + len(sent) < MAX_CHUNK:
                    last.text += " " + sent
                    continue
                segments.append(Segment(sent, role, style, PAUSE_AFTER[style]))
            if len(segments) > role_start:
                segments[-1].pause_after = ROLE_SWITCH_PAUSE + PAUSE_AFTER[segments[-1].style] // 2
        if len(segments) > para_start:
            segments[-1].pause_after = PAUSE_AFTER[segments[-1].style] + PARAGRAPH_PAUSE
    return segments
