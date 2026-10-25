"""Воспроизведение MP3: через ffplay, если он есть, иначе системным проигрывателем."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


class Player:
    def __init__(self) -> None:
        self._proc: subprocess.Popen | None = None

    @property
    def can_stop(self) -> bool:
        return shutil.which("ffplay") is not None

    def play(self, path: str | Path) -> None:
        self.stop()
        path = str(path)
        if shutil.which("ffplay"):
            self._proc = subprocess.Popen(
                ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", path],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        elif sys.platform.startswith("win"):
            os.startfile(path)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            self._proc = subprocess.Popen(["afplay", path])
        else:
            subprocess.Popen(["xdg-open", path])

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
        self._proc = None

    def wait(self) -> None:
        if self._proc:
            self._proc.wait()
