package ru.chtec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.chtec.core.EdgeTts;
import ru.chtec.core.Roles;

/** Раздаёт персонажам голоса: разный голос, высота и темп у каждого — и так на всю книгу. */
final class RoleVoices {
    private RoleVoices() {}

    // Сдвиги {высота Гц, темп %}: персонажи одного пола с одним голосом звучат по-разному.
    private static final int[][] VARIANTS = {
            {-6, 0}, {7, 4}, {-12, -4}, {12, 6}, {-3, 8}, {3, -6}, {-16, 2}, {16, -2}, {-9, 5}, {9, -5}};

    /**
     * Объединяет найденных в тексте персонажей с сохранёнными. Новым (или всем при reassign) раздаёт голоса.
     * @param phoneRu русские голоса телефона («пакет|имя»)
     */
    static Map<String, Library.Role> merge(Map<String, Roles.Person> found, Map<String, Library.Role> saved,
                                           List<String> phoneRu, Settings s, boolean reassign) {
        Map<String, Library.Role> out = new LinkedHashMap<>();
        int[] used = new int[3]; // по полу: м, ж, ?
        int phoneIdx = 0;
        List<String> phonePool = new ArrayList<>();
        for (String k : phoneRu) if (!k.equals(s.systemNarrator)) phonePool.add(k); // не голосом рассказчика
        if (phonePool.isEmpty()) phonePool.addAll(phoneRu);

        // Сначала оставляем уже настроенных (чтобы их голоса не менялись), потом раздаём новым.
        List<Library.Role> fresh = new ArrayList<>();
        for (Roles.Person p : found.values()) {
            Library.Role r = saved.get(p.name);
            if (r == null || reassign) {
                Library.Role n = new Library.Role();
                n.name = p.name;
                n.gender = r != null && r.gender != '?' && !reassign ? r.gender : p.gender();
                if (r != null) n.off = r.off;
                fresh.add(n);
                r = n;
            } else {
                used[slot(r.gender)]++;
                phoneIdx++;
            }
            r.lines = p.lines;
            out.put(r.name, r);
        }
        // Сохранённые персонажи, которых в этом тексте не нашли, тоже оставляем — вдруг появятся дальше.
        for (Library.Role r : saved.values()) if (!out.containsKey(r.name)) out.put(r.name, r);

        for (Library.Role r : fresh) {
            if (r.name.equals(Roles.HERO)) {
                // Герой-рассказчик говорит голосом рассказчика.
                r.edgeVoice = s.edgeNarrator;
                r.phoneVoice = s.systemNarrator;
                r.pitch = 0;
                r.rate = 0;
                continue;
            }
            char g = r.gender;
            if (g == '?') g = used[0] <= used[1] ? 'm' : 'f';
            r.edgeVoice = g == 'f' ? EdgeTts.SVETLANA : EdgeTts.DMITRY;
            int[] v = VARIANTS[used[slot(g)]++ % VARIANTS.length];
            r.pitch = v[0];
            r.rate = v[1];
            r.phoneVoice = phonePool.isEmpty() ? "" : phonePool.get(phoneIdx++ % phonePool.size());
        }
        return out;
    }

    private static int slot(char g) {
        return g == 'm' ? 0 : g == 'f' ? 1 : 2;
    }

    /** Короткое описание голоса персонажа для списка. */
    static String describe(Library.Role r, boolean microsoft, List<PhoneVoices.Item> phone) {
        if (r.off) return "голосом рассказчика";
        String voice;
        if (microsoft) {
            voice = EdgeTts.SVETLANA.equals(r.edgeVoice) ? "Светлана" : "Дмитрий";
        } else {
            voice = "стандартный голос";
            for (PhoneVoices.Item it : phone) if (it.key.equals(r.phoneVoice)) voice = it.label;
        }
        String tone = r.pitch == 0 ? "" : r.pitch < 0 ? ", ниже" : ", выше";
        String speed = r.rate == 0 ? "" : r.rate < 0 ? ", медленнее" : ", быстрее";
        return voice + tone + speed;
    }
}
