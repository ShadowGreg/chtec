import ru.chtec.core.*;
import java.nio.file.*;
import java.util.*;
public class RolesPerf {
    public static void main(String[] a) throws Exception {
        List<Block> loaded = TextExtractor.load(Files.readAllBytes(Paths.get(a[0])), a[0]);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 5; r++) for (Block b : loaded) sb.append(b.text).append("\n\n");
        List<Block> blocks = TextExtractor.plainToBlocks(sb.toString());
        List<Segment> segs = Prepare.buildScore(blocks, true);
        long t = System.nanoTime();
        Map<String, Roles.Person> cast = Roles.attribute(segs);
        System.out.println("знаков " + sb.length() + ", фраз " + segs.size() + ", персонажей " + cast.size() + ", за " + (System.nanoTime() - t) / 1000000 + " мс");
    }
}
