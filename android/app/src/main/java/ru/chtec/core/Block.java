package ru.chtec.core;

/** Абзац или заголовок исходного текста. start/end — положение в тексте редактора (если известно). */
public final class Block {
    public final String kind; // "heading" | "para"
    public final String text;
    public final int start;
    public final int end;

    public Block(String kind, String text) {
        this(kind, text, -1, -1);
    }

    public Block(String kind, String text, int start, int end) {
        this.kind = kind;
        this.text = text;
        this.start = start;
        this.end = end;
    }
}
