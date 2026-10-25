package ru.chtec.core;

/** Фрагмент «партитуры»: что читать, чьим голосом, с какой интонацией и сколько молчать после. */
public final class Segment {
    public String text;
    public final String role;  // "narrator" | "character"
    public final String style; // ключ из Prepare.PROSODY
    public int pauseAfter;     // мс
    public final int block;    // номер абзаца, из которого взят фрагмент

    // Где фрагмент и каждое его слово стоят в тексте на экране (заполняет Aligner; -1 — неизвестно).
    public int srcStart = -1, srcEnd = -1;
    public int[] wordStart = new int[0], wordEnd = new int[0];

    // Роль: кто говорит (заполняет Roles) и каким голосом его читать (заполняет экран по списку ролей).
    public String speaker;
    public String edgeVoice;  // голос Microsoft персонажа или null — как обычно
    public String phoneVoice; // голос телефона персонажа («пакет|имя», "" — стандартный) или null
    public int pitchShift, rateShift;

    public Segment(String text, String role, String style, int pauseAfter, int block) {
        this.text = text;
        this.role = role;
        this.style = style;
        this.pauseAfter = pauseAfter;
        this.block = block;
    }
}
