import ru.chtec.core.*;
import java.nio.file.*;
import java.util.*;
public class AlignCheck {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Paths.get(a[0]));
        List<Block> loaded = TextExtractor.load(d, Paths.get(a[0]).getFileName().toString());
        StringBuilder sb = new StringBuilder();
        for (Block b : loaded) { if (sb.length() > 0) sb.append("\n\n"); sb.append(b.text); }
        String screen = "ПРЕДИСЛОВИЕ\n\n" + sb;           // как на экране, с текстом до места чтения
        int base = 13;                                     // читаем «от курсора»
        String src = screen.substring(base);
        List<Block> blocks = TextExtractor.plainToBlocks(src);
        List<Segment> segs = Prepare.buildScore(blocks, true);
        Aligner.align(segs, blocks, src, base);
        int bad = 0;
        for (Segment s : segs) {
            String shown = s.srcStart >= 0 ? screen.substring(s.srcStart, s.srcEnd) : "<нет>";
            boolean wordsOk = true;
            List<int[]> w = Aligner.words(s.text);
            for (int k = 0; k < w.size(); k++) {
                String sw = s.text.substring(w.get(k)[0], w.get(k)[1]).toLowerCase();
                String on = screen.substring(s.wordStart[k], s.wordEnd[k]).toLowerCase();
                if (!sw.equals(on)) wordsOk = false;
            }
            if (!wordsOk) bad++;
            if (a.length > 1) System.out.println((wordsOk ? "  " : "!!") + " [" + s.text + "]  ->  [" + shown.replace('\n', '⏎') + "]");
        }
        int[] est = Timing.estimate("Привет, мир! Как дела?", 2000);
        int[] fb = Timing.fromBoundaries("Привет, мир! Как дела?", new long[]{100, 700, 1300}, new String[]{"Привет", "мир", "дела"}, 2000);
        System.out.println(a[0].replaceAll(".*/", "") + ": фрагментов " + segs.size() + ", со сбоем слов " + bad
                + " | оценка " + Arrays.toString(est) + " | по меткам " + Arrays.toString(fb) + " | слово@1000мс=" + Timing.wordAt(fb, 1000));
    }
}
