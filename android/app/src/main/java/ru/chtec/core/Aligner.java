package ru.chtec.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Привязывает слова каждого фрагмента к их месту в исходном тексте — для подсветки.
 * Фрагмент прошёл нормализацию (тире, многоточия, убранные ссылки), поэтому сравниваем по словам, а не по символам.
 */
public final class Aligner {
    private Aligner() {}

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int LOOKAHEAD = 12;

    /** Слова строки: пары {начало, конец}. */
    public static List<int[]> words(String s) {
        List<int[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(s);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    static String key(String w) {
        return w.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /**
     * @param source текст, из которого строились blocks (их start/end — позиции в нём)
     * @param base   смещение source в тексте на экране
     */
    public static void align(List<Segment> segs, List<Block> blocks, String source, int base) {
        int curBlock = -1;
        List<int[]> src = new ArrayList<>();
        List<String> srcKeys = new ArrayList<>();
        int p = 0;
        for (Segment seg : segs) {
            if (seg.block != curBlock) {
                curBlock = seg.block;
                src.clear();
                srcKeys.clear();
                p = 0;
                Block b = seg.block >= 0 && seg.block < blocks.size() ? blocks.get(seg.block) : null;
                if (b != null && b.start >= 0 && b.end <= source.length()) {
                    String bt = source.substring(b.start, b.end);
                    for (int[] w : words(bt)) {
                        src.add(new int[]{b.start + w[0], b.start + w[1]});
                        srcKeys.add(key(bt.substring(w[0], w[1])));
                    }
                }
            }
            List<int[]> sw = words(seg.text);
            int n = sw.size();
            seg.wordStart = new int[n];
            seg.wordEnd = new int[n];
            for (int k = 0; k < n; k++) {
                String wk = key(seg.text.substring(sw.get(k)[0], sw.get(k)[1]));
                int found = -1;
                for (int q = p; q < Math.min(p + LOOKAHEAD, src.size()); q++) {
                    if (srcKeys.get(q).equals(wk)) {
                        found = q;
                        break;
                    }
                }
                if (found >= 0) {
                    seg.wordStart[k] = base + src.get(found)[0];
                    seg.wordEnd[k] = base + src.get(found)[1];
                    p = found + 1;
                } else {
                    seg.wordStart[k] = -1;
                    seg.wordEnd[k] = -1;
                }
            }
            // Слова, которых нет в исходнике (например «номер» вместо «№»), подсвечиваем вместе с соседом.
            for (int k = 1; k < n; k++) {
                if (seg.wordStart[k] < 0) {
                    seg.wordStart[k] = seg.wordStart[k - 1];
                    seg.wordEnd[k] = seg.wordEnd[k - 1];
                }
            }
            for (int k = n - 2; k >= 0; k--) {
                if (seg.wordStart[k] < 0) {
                    seg.wordStart[k] = seg.wordStart[k + 1];
                    seg.wordEnd[k] = seg.wordEnd[k + 1];
                }
            }
            seg.srcStart = n > 0 ? seg.wordStart[0] : -1;
            seg.srcEnd = n > 0 ? seg.wordEnd[n - 1] : -1;
            if (seg.srcEnd >= 0) {
                // Захватываем знаки в конце предложения: «…», «?!», закрывающие кавычки.
                int abs = seg.srcEnd - base;
                while (abs < source.length() && ".,;:!?…»\")".indexOf(source.charAt(abs)) >= 0) abs++;
                seg.srcEnd = base + abs;
            }
        }
    }
}
