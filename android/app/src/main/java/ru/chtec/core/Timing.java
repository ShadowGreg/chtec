package ru.chtec.core;

import java.util.List;

/** Когда звучит каждое слово фрагмента (мс от начала его звука) — для бегущей подсветки. */
public final class Timing {
    private Timing() {}

    /** По меткам слов от голосов Microsoft. Слова без метки получают время по соседям. */
    public static int[] fromBoundaries(String segText, long[] offsetsMs, String[] texts, int speechMs) {
        List<int[]> words = Aligner.words(segText);
        int n = words.size();
        int[] ms = new int[n];
        for (int i = 0; i < n; i++) ms[i] = -1;
        int p = 0;
        for (int b = 0; b < offsetsMs.length; b++) {
            List<int[]> bw = Aligner.words(texts[b]);
            if (bw.isEmpty()) continue;
            String first = Aligner.key(texts[b].substring(bw.get(0)[0], bw.get(0)[1]));
            for (int q = p; q < Math.min(p + 6, n); q++) {
                if (Aligner.key(segText.substring(words.get(q)[0], words.get(q)[1])).equals(first)) {
                    ms[q] = (int) offsetsMs[b];
                    p = q + 1;
                    break;
                }
            }
        }
        if (n > 0 && ms[0] < 0) ms[0] = 0;
        // Пропуски заполняем, распределяя время пропорционально положению в тексте.
        int[] est = estimate(segText, speechMs);
        int last = 0;
        for (int i = 1; i <= n; i++) {
            if (i < n && ms[i] < 0) continue;
            int next = i < n ? ms[i] : Math.max(speechMs, ms[last]);
            for (int k = last + 1; k < i; k++) {
                double span = est[Math.min(i, n - 1)] - est[last];
                double t = i < n && span > 0 ? (est[k] - est[last]) / span : (double) (k - last) / (i - last);
                ms[k] = ms[last] + (int) Math.round((next - ms[last]) * t);
            }
            last = i;
        }
        for (int i = 1; i < n; i++) if (ms[i] < ms[i - 1]) ms[i] = ms[i - 1];
        return ms;
    }

    /** Оценка для голоса телефона: время пропорционально длине текста, знаки препинания «весят» как паузы. */
    public static int[] estimate(String segText, int speechMs) {
        List<int[]> words = Aligner.words(segText);
        int n = words.size();
        double[] weightBefore = new double[segText.length() + 1];
        double w = 0;
        for (int i = 0; i < segText.length(); i++) {
            weightBefore[i] = w;
            char c = segText.charAt(i);
            if (c == ',' || c == ';') w += 4;
            else if (".!?…:—–".indexOf(c) >= 0) w += 6;
            else w += 1;
        }
        weightBefore[segText.length()] = w;
        int[] ms = new int[n];
        for (int k = 0; k < n; k++) {
            ms[k] = w > 0 ? (int) Math.round(speechMs * weightBefore[words.get(k)[0]] / w) : 0;
        }
        return ms;
    }

    /** Номер слова, которое звучит в момент posMs. */
    public static int wordAt(int[] wordMs, int posMs) {
        int lo = 0, hi = wordMs.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (wordMs[mid] <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
