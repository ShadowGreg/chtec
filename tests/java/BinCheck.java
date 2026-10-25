import ru.chtec.core.TextExtractor;
import java.io.*; import java.nio.file.*;
public class BinCheck {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            byte[] all = Files.readAllBytes(Paths.get(p));
            byte[] head = java.util.Arrays.copyOf(all, Math.min(all.length, 256 * 1024));
            long t = System.nanoTime();
            boolean doc = head.length > 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF;
            String text = TextExtractor.decodeBytes(head);
            int cyr = 0;
            for (int i = 0; i < text.length() && cyr < 200; i++) { char ch = text.charAt(i); if ((ch >= 'а' && ch <= 'я') || (ch >= 'А' && ch <= 'Я') || ch == 'ё' || ch == 'Ё') cyr++; }
            System.out.printf("%-22s %s  (%d мс)%n", Paths.get(p).getFileName(), (doc || cyr >= 200) ? "В БИБЛИОТЕКУ" : "пропуск (" + cyr + " рус. букв)", (System.nanoTime() - t) / 1000000);
        }
    }
}
