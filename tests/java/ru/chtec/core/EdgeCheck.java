package ru.chtec.core;
import java.nio.file.*;
import java.util.Arrays;
public class EdgeCheck {
    public static void main(String[] a) throws Exception {
        EdgeTts.host = "127.0.0.1"; EdgeTts.port = Integer.parseInt(a[0]); EdgeTts.tls = false;
        EdgeTts.Speech sp = EdgeTts.synthesize("Привет! Как дела? <тест> & «кавычки»…", EdgeTts.SVETLANA, -5, 10, 10);
        Files.write(Paths.get(a[1]), sp.audio);
        System.out.println("bytes=" + sp.audio.length + " ms=" + sp.durationMs() + " words=" + Arrays.toString(sp.words) + " at=" + Arrays.toString(sp.offsetsMs)
            + " skew=" + Math.round(EdgeDrm.clockSkewSeconds));
    }
}
