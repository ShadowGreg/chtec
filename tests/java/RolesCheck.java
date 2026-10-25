import ru.chtec.core.*;
import java.nio.file.*;
import java.util.*;
public class RolesCheck {
    public static void main(String[] a) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        List<Block> blocks = TextExtractor.plainToBlocks(text);
        List<Segment> segs = Prepare.buildScore(blocks, true);
        Map<String, Roles.Person> cast = Roles.attribute(segs);
        List<String> got = new ArrayList<>();
        int lastBlock = -1;
        for (Segment s : segs) {
            if (!s.role.equals("character") || s.block == lastBlock) continue;
            lastBlock = s.block;
            got.add(s.speaker == null ? "?" : s.speaker);
        }
        List<String> exp = a.length > 1 ? Files.readAllLines(Paths.get(a[1])) : new ArrayList<String>();
        int ok = 0;
        for (int i = 0; i < got.size(); i++) {
            String e = i < exp.size() ? exp.get(i) : "";
            boolean hit = got.get(i).equals(e);
            if (hit) ok++;
            System.out.println((hit ? "  " : "✗ ") + String.format("%-18s", got.get(i)) + (hit ? "" : " (ожидалось: " + e + ")"));
        }
        System.out.println("Реплик: " + got.size() + ", верно: " + ok);
        for (Roles.Person p : cast.values()) System.out.println("  персонаж: " + p.name + " [" + p.gender() + "] реплик " + p.lines);
    }
}
