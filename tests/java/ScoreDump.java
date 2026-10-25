import ru.chtec.core.*;
import java.nio.file.*;
import java.util.*;
public class ScoreDump {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Paths.get(a[0]));
        List<Block> blocks = TextExtractor.load(d, Paths.get(a[0]).getFileName().toString());
        for (Segment s : Prepare.buildScore(blocks, true))
            System.out.println(String.format("[%-9s %-16s пауза %4d мс] %s", s.role, s.style, s.pauseAfter, s.text));
    }
}
