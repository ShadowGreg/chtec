"""Синтез речи нейросетевыми голосами Microsoft (через edge-tts) и сборка в один MP3."""
from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import edge_tts

from .prepare import PROSODY, Segment

VOICES = {
    "Дмитрий (мужской)": "ru-RU-DmitryNeural",
    "Светлана (женский)": "ru-RU-SvetlanaNeural",
}

# Один кадр тишины MP3 (MPEG-2 Layer III, 24 кГц, моно, 48 кбит/с) — в том же формате,
# что отдаёт сервис, поэтому кадры можно просто склеивать без перекодирования.
_SILENT_FRAME = base64.b64decode(
    "//NkxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"
    "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"
    "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"
)
_FRAME_MS = 24  # 576 отсчётов / 24000 Гц


@dataclass
class VoiceSettings:
    narrator_voice: str = "ru-RU-DmitryNeural"
    character_voice: str = ""  # пусто — тот же голос, что у рассказчика
    rate: int = 0       # %, общий темп
    pitch: int = 0      # Гц, общая высота
    volume: int = 0     # %
    concurrency: int = 4


def silence(ms: int) -> bytes:
    return _SILENT_FRAME * max(0, round(ms / _FRAME_MS))


def _signed(value: int, unit: str) -> str:
    return f"{value:+d}{unit}"


async def _synth_one(seg: Segment, s: VoiceSettings) -> bytes:
    d_rate, d_pitch, d_vol = PROSODY.get(seg.style, (0, 0, 0))
    voice = s.narrator_voice
    if seg.role == "character":
        if s.character_voice:
            voice = s.character_voice
        else:
            # Тот же актёр, но реплики персонажей чуть живее и выше, чем авторский текст.
            d_rate += 3
            d_pitch += 3
    comm = edge_tts.Communicate(
        seg.text,
        voice,
        rate=_signed(s.rate + d_rate, "%"),
        pitch=_signed(s.pitch + d_pitch, "Hz"),
        volume=_signed(max(-100, s.volume + d_vol), "%"),
    )
    audio = bytearray()
    async for chunk in comm.stream():
        if chunk["type"] == "audio":
            audio += chunk["data"]
    return bytes(audio)


async def synthesize(
    segments: list[Segment],
    out_path: str | Path,
    settings: VoiceSettings | None = None,
    progress: Callable[[int, int], None] | None = None,
    cancel: Callable[[], bool] | None = None,
) -> Path:
    """Озвучивает партитуру и сохраняет в MP3. progress(готово, всего) вызывается по мере работы."""
    settings = settings or VoiceSettings()
    out_path = Path(out_path)
    results: list[bytes | None] = [None] * len(segments)
    sem = asyncio.Semaphore(settings.concurrency)
    done = 0

    async def worker(i: int, seg: Segment) -> None:
        nonlocal done
        async with sem:
            if cancel and cancel():
                raise asyncio.CancelledError
            for attempt in range(4):
                try:
                    results[i] = await _synth_one(seg, settings)
                    break
                except edge_tts.exceptions.NoAudioReceived:
                    results[i] = b""  # фрагмент без произносимых слов
                    break
                except Exception:
                    if attempt == 3:
                        raise
                    await asyncio.sleep(2 ** attempt)
        done += 1
        if progress:
            progress(done, len(segments))

    await asyncio.gather(*(worker(i, seg) for i, seg in enumerate(segments)))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(silence(200))
        for seg, audio in zip(segments, results):
            f.write(audio or b"")
            f.write(silence(seg.pause_after))
    return out_path
