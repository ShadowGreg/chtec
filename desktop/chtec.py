#!/usr/bin/env python3
"""Чтец — читает вслух русские тексты из .doc/.docx/.md/.bin/.txt голосом с актёрской интонацией.

Запуск без аргументов открывает окно программы.
Командная строка:  python chtec.py книга.doc -o книга.mp3 --play
"""
from __future__ import annotations

import argparse
import asyncio
import sys
import threading
from pathlib import Path

from reader.extract import Block, load_blocks, plain_to_blocks
from reader.player import Player
from reader.prepare import build_score
from reader.speak import VOICES, VoiceSettings, synthesize

SHORT_VOICES = {"dmitry": "ru-RU-DmitryNeural", "svetlana": "ru-RU-SvetlanaNeural"}
FILE_TYPES = [("Тексты", "*.doc *.docx *.md *.markdown *.bin *.txt *.rtf"), ("Все файлы", "*.*")]


def _voice(name: str) -> str:
    return SHORT_VOICES.get(name.lower(), name) if name else ""


# ---------------------------------------------------------------- командная строка

def run_cli(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Озвучивание русского текста с интонацией.")
    p.add_argument("file", help="файл .doc, .docx, .md, .bin, .txt")
    p.add_argument("-o", "--out", help="куда сохранить MP3 (по умолчанию рядом с файлом)")
    p.add_argument("--voice", default="dmitry", help="голос рассказчика: dmitry, svetlana или полное имя голоса")
    p.add_argument("--dialog-voice", default="", help="отдельный голос для реплик персонажей")
    p.add_argument("--rate", type=int, default=0, help="темп, %% (например -10 или 15)")
    p.add_argument("--pitch", type=int, default=0, help="высота голоса, Гц")
    p.add_argument("--flat", action="store_true", help="ровное чтение без актёрской интонации")
    p.add_argument("--play", action="store_true", help="воспроизвести после озвучки")
    p.add_argument("--score", action="store_true", help="только показать разметку интонаций, без озвучки")
    a = p.parse_args(argv)

    blocks = load_blocks(a.file)
    score = build_score(blocks, expressive=not a.flat)
    if not score:
        print("В файле не найден текст для чтения.", file=sys.stderr)
        return 1

    if a.score:
        for seg in score:
            print(f"[{seg.role:9} {seg.style:16} пауза {seg.pause_after:4} мс] {seg.text}")
        return 0

    out = Path(a.out) if a.out else Path(a.file).with_suffix(".mp3")
    settings = VoiceSettings(_voice(a.voice), _voice(a.dialog_voice), a.rate, a.pitch)

    def progress(done: int, total: int) -> None:
        print(f"\rОзвучено {done}/{total} фрагментов", end="", flush=True)

    asyncio.run(synthesize(score, out, settings, progress))
    print(f"\nГотово: {out}")
    if a.play:
        player = Player()
        player.play(out)
        player.wait()
    return 0


# ---------------------------------------------------------------- окно

def run_gui() -> None:
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    root = tk.Tk()
    root.title("Чтец")
    root.geometry("820x620")
    player = Player()
    state = {"file": None, "cancel": False, "busy": False}

    # Верхняя панель
    top = ttk.Frame(root, padding=8)
    top.pack(fill="x")
    file_var = tk.StringVar(value="Файл не выбран")
    ttk.Button(top, text="Открыть файл…", command=lambda: open_file()).pack(side="left")
    ttk.Label(top, textvariable=file_var).pack(side="left", padx=8)

    opts = ttk.LabelFrame(root, text="Голос", padding=8)
    opts.pack(fill="x", padx=8)
    voice_names = list(VOICES)
    narrator = tk.StringVar(value=voice_names[0])
    dialog = tk.StringVar(value="Тот же")
    rate = tk.IntVar(value=0)
    pitch = tk.IntVar(value=0)
    expressive = tk.BooleanVar(value=True)

    ttk.Label(opts, text="Рассказчик:").grid(row=0, column=0, sticky="w")
    ttk.Combobox(opts, textvariable=narrator, values=voice_names, state="readonly", width=22).grid(row=0, column=1, padx=4)
    ttk.Label(opts, text="Реплики персонажей:").grid(row=0, column=2, sticky="w", padx=(12, 0))
    ttk.Combobox(opts, textvariable=dialog, values=["Тот же"] + voice_names, state="readonly", width=22).grid(row=0, column=3, padx=4)
    ttk.Label(opts, text="Темп, %:").grid(row=1, column=0, sticky="w", pady=(6, 0))
    tk.Scale(opts, from_=-50, to=50, variable=rate, orient="horizontal", length=180).grid(row=1, column=1, sticky="we")
    ttk.Label(opts, text="Высота, Гц:").grid(row=1, column=2, sticky="w", padx=(12, 0), pady=(6, 0))
    tk.Scale(opts, from_=-30, to=30, variable=pitch, orient="horizontal", length=180).grid(row=1, column=3, sticky="we")
    ttk.Checkbutton(opts, text="Актёрская интонация (вопросы, восклицания, многоточия, диалоги)",
                    variable=expressive).grid(row=2, column=0, columnspan=4, sticky="w", pady=(6, 0))

    text = tk.Text(root, wrap="word", font=("Segoe UI", 11), undo=True)
    text.pack(fill="both", expand=True, padx=8, pady=8)
    text.insert("1.0", "Откройте файл или вставьте текст сюда.\n"
                       "Если выделить часть текста — будет прочитан только выделенный фрагмент.")

    bottom = ttk.Frame(root, padding=(8, 0, 8, 8))
    bottom.pack(fill="x")
    progress = ttk.Progressbar(bottom, length=220)
    status = tk.StringVar(value="")
    b_listen = ttk.Button(bottom, text="▶ Слушать", command=lambda: start(save=False))
    b_save = ttk.Button(bottom, text="Сохранить MP3…", command=lambda: start(save=True))
    b_stop = ttk.Button(bottom, text="■ Стоп", command=lambda: stop())
    for w in (b_listen, b_save, b_stop):
        w.pack(side="left", padx=(0, 6))
    progress.pack(side="left", padx=6)
    ttk.Label(bottom, textvariable=status).pack(side="left")

    def open_file() -> None:
        path = filedialog.askopenfilename(filetypes=FILE_TYPES)
        if not path:
            return
        try:
            blocks = load_blocks(path)
        except Exception as e:
            messagebox.showerror("Чтец", f"Не удалось прочитать файл:\n{e}")
            return
        state["file"] = path
        file_var.set(Path(path).name)
        text.delete("1.0", "end")
        text.insert("1.0", "\n\n".join(b.text for b in blocks))

    def current_blocks() -> list[Block]:
        try:
            chunk = text.get("sel.first", "sel.last")
        except tk.TclError:
            chunk = text.get("1.0", "end")
        return plain_to_blocks(chunk)

    def settings() -> VoiceSettings:
        d = dialog.get()
        return VoiceSettings(VOICES[narrator.get()], VOICES.get(d, ""), rate.get(), pitch.get())

    def start(save: bool) -> None:
        if state["busy"]:
            return
        score = build_score(current_blocks(), expressive=expressive.get())
        if not score:
            messagebox.showinfo("Чтец", "Нет текста для чтения.")
            return
        if save:
            initial = Path(state["file"]).with_suffix(".mp3").name if state["file"] else "озвучка.mp3"
            out = filedialog.asksaveasfilename(defaultextension=".mp3", initialfile=initial,
                                               filetypes=[("MP3", "*.mp3")])
            if not out:
                return
        else:
            import tempfile
            out = str(Path(tempfile.gettempdir()) / "chtec_preview.mp3")

        player.stop()
        state.update(busy=True, cancel=False)
        progress["value"] = 0
        status.set("Озвучиваю…")

        def on_progress(done: int, total: int) -> None:
            root.after(0, lambda: (progress.configure(value=100 * done / total),
                                   status.set(f"Озвучено {done} из {total}")))

        voice = settings()  # читаем настройки окна здесь: tkinter нельзя трогать из другого потока

        def work() -> None:
            try:
                asyncio.run(synthesize(score, out, voice, on_progress, lambda: state["cancel"]))
            except BaseException as e:
                err = str(e) or type(e).__name__
                msg = "Остановлено." if state["cancel"] else f"Ошибка: {err}"
                root.after(0, lambda: status.set(msg))
                if not state["cancel"]:
                    root.after(0, lambda: messagebox.showerror(
                        "Чтец", f"Не удалось озвучить текст.\nНужен доступ в интернет.\n\n{err}"))
            else:
                root.after(0, lambda: status.set(f"Готово: {out}" if save else "Воспроизведение…"))
                root.after(0, lambda: player.play(out))
            finally:
                state["busy"] = False

        threading.Thread(target=work, daemon=True).start()

    def stop() -> None:
        state["cancel"] = True
        player.stop()
        if not state["busy"]:
            status.set("")

    root.protocol("WM_DELETE_WINDOW", lambda: (player.stop(), root.destroy()))
    root.mainloop()


if __name__ == "__main__":
    if len(sys.argv) > 1:
        sys.exit(run_cli(sys.argv[1:]))
    run_gui()
