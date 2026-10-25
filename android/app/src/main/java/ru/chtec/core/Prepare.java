package ru.chtec.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подготовка текста к чтению: нормализация, разбиение на предложения,
 * интонация по знакам препинания и разметка диалогов (перенос reader/prepare.py).
 */
public final class Prepare {
    private Prepare() {}

    /** Интонация -> {скорость %, высота Гц, громкость %} поверх общих настроек. */
    public static final Map<String, int[]> PROSODY = new HashMap<>();
    /** Пауза после фрагмента, мс. */
    public static final Map<String, Integer> PAUSE_AFTER = new HashMap<>();

    static {
        PROSODY.put("statement", new int[]{0, 0, 0});
        PROSODY.put("question", new int[]{0, 6, 0});
        PROSODY.put("exclaim", new int[]{6, 10, 10});
        PROSODY.put("question_exclaim", new int[]{6, 12, 10});
        PROSODY.put("ellipsis", new int[]{-12, -4, -5});
        PROSODY.put("heading", new int[]{-10, -2, 5});

        PAUSE_AFTER.put("statement", 350);
        PAUSE_AFTER.put("question", 450);
        PAUSE_AFTER.put("exclaim", 450);
        PAUSE_AFTER.put("question_exclaim", 500);
        PAUSE_AFTER.put("ellipsis", 800);
        PAUSE_AFTER.put("heading", 900);
    }

    public static final int PARAGRAPH_PAUSE = 450;
    public static final int ROLE_SWITCH_PAUSE = 150;
    public static final int MAX_CHUNK = 1500;

    // ------------------------------------------------------------ нормализация

    private static final Pattern URL = Pattern.compile("https?://\\S+|www\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\w+");
    private static final Pattern DOTS = Pattern.compile("\\.{3,}|…+");
    private static final Pattern EXCL_DOTS = Pattern.compile("([!?])\\.{2}");
    private static final Pattern MID_DASH = Pattern.compile("(?<=\\s)[-–](?=\\s)");
    private static final Pattern START_DASH = Pattern.compile("^[-–](?=\\s)");
    private static final Pattern JUNK = Pattern.compile("[*#_~|<>\\[\\]{}^\\\\]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE = Pattern.compile("\\s+([,.!?…:;»)])");
    private static final Pattern SPACE_AFTER = Pattern.compile("([«(])\\s+");

    public static String normalize(String text) {
        text = text.replace(' ', ' ').replace("­", "");
        text = URL.matcher(text).replaceAll("");
        text = EMAIL.matcher(text).replaceAll("");
        text = DOTS.matcher(text).replaceAll("…");
        text = EXCL_DOTS.matcher(text).replaceAll("$1…");
        text = MID_DASH.matcher(text).replaceAll("—");
        text = START_DASH.matcher(text).replaceAll("—");
        text = text.replace("№", "номер ").replace("&", " и ").replace("§", "параграф ");
        text = JUNK.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ");
        text = SPACE_BEFORE.matcher(text).replaceAll("$1");
        text = SPACE_AFTER.matcher(text).replaceAll("$1");
        return text.trim();
    }

    // ------------------------------------------------------------ предложения

    private static final String NB = "(?<![\\p{L}\\p{N}_])"; // граница слова, понятная и для кириллицы
    private static final Pattern ABBR = Pattern.compile(
            "(?:" + NB + "(?:т\\.\\s?е|т\\.\\s?д|т\\.\\s?п|т\\.\\s?к|и\\.\\s?о|т|е|д|п|г|гг|в|вв|см|ср|др|пр|им|ул|стр|рис|тыс|млн|млрд"
                    + "|руб|коп|акад|проф|доц|ст|гл|изд|напр|прим|ред|англ|лат|франц|нем|р|н\\.\\s?э|с|л|кв|обл|пос)"
                    + "|" + NB + "[А-ЯЁA-Z])\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SENT = Pattern.compile("[^.!?…]*(?:[.!?…]+[»\")]*|$)");
    private static final Pattern STARTS_LOWER = Pattern.compile("^[«(\"]?[а-яёa-z0-9,;:]");

    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SENT.matcher(text);
        while (m.find()) {
            String s = m.group().trim();
            if (s.isEmpty()) continue;
            if (!out.isEmpty()) {
                String prev = out.get(out.size() - 1);
                boolean startsLower = STARTS_LOWER.matcher(s).find();
                boolean abbr = ABBR.matcher(prev).find()
                        && !(prev.endsWith("!") || prev.endsWith("?") || prev.endsWith("…"));
                if (startsLower || abbr) {
                    out.set(out.size() - 1, prev + " " + s);
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    private static final Pattern TRAIL = Pattern.compile("[»\")\\s]+$");

    public static String sentenceStyle(String sentence) {
        String t = TRAIL.matcher(sentence).replaceAll("");
        String tail = t.length() > 3 ? t.substring(t.length() - 3) : t;
        boolean q = tail.contains("?"), e = tail.contains("!");
        if (q && e) return "question_exclaim";
        if (tail.endsWith("…") && !tail.matches(".*[!?]…$")) return "ellipsis";
        if (q) return "question";
        if (e) return "exclaim";
        return "statement";
    }

    // ------------------------------------------------------------ диалоги

    private static final Pattern DIALOG_START = Pattern.compile("^[—–]\\s*");
    private static final Pattern DASH = Pattern.compile("\\s+[—–]\\s+");
    private static final Pattern END_SPEECH = Pattern.compile("[,!?…]$");
    private static final Pattern END_AUTHOR = Pattern.compile("[.,:]$");
    private static final Pattern LOWER_CYR = Pattern.compile("^[а-яё]");

    /** «— Привет, — сказал он. — Как дела?» -> реплика / слова автора / реплика. Пары {роль, текст}. */
    public static List<String[]> splitDialog(String paragraph) {
        List<String[]> res = new ArrayList<>();
        if (!DIALOG_START.matcher(paragraph).find()) {
            res.add(new String[]{"narrator", paragraph});
            return res;
        }
        String body = DIALOG_START.matcher(paragraph).replaceFirst("");
        String[] parts = DASH.split(body, -1);
        res.add(new String[]{"character", parts[0]});
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            String[] last = res.get(res.size() - 1);
            if (last[0].equals("character") && END_SPEECH.matcher(last[1]).find() && LOWER_CYR.matcher(part).find()) {
                res.add(new String[]{"narrator", part});
            } else if (last[0].equals("narrator") && END_AUTHOR.matcher(last[1]).find()) {
                res.add(new String[]{"character", part});
            } else {
                last[1] = last[1] + " — " + part;
            }
        }
        List<String[]> cleaned = new ArrayList<>();
        for (int i = 0; i < res.size(); i++) {
            String text = res.get(i)[1].trim();
            // Запятая перед словами автора в устной речи звучит как конец фразы.
            if (text.endsWith(",") && i + 1 < res.size()) text = text.substring(0, text.length() - 1) + ".";
            if (!text.isEmpty()) cleaned.add(new String[]{res.get(i)[0], text});
        }
        return cleaned;
    }

    // ------------------------------------------------------------ партитура

    private static final Pattern END_PUNCT = Pattern.compile("[.!?…]$");

    public static List<Segment> buildScore(List<Block> blocks, boolean expressive) {
        List<Segment> segs = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            String text = normalize(block.text);
            if (text.isEmpty()) continue;
            if (block.kind.equals("heading")) {
                if (!END_PUNCT.matcher(text).find()) text += ".";
                segs.add(new Segment(text, "narrator", expressive ? "heading" : "statement", PAUSE_AFTER.get("heading"), b));
                continue;
            }
            int paraStart = segs.size();
            for (String[] rp : splitDialog(text)) {
                int roleStart = segs.size();
                for (String sent : splitSentences(rp[1])) {
                    String style = expressive ? sentenceStyle(sent) : "statement";
                    Segment last = segs.size() > roleStart ? segs.get(segs.size() - 1) : null;
                    // Подряд идущие предложения с одной интонацией читаем одним куском.
                    if (last != null && last.style.equals(style) && last.text.length() + sent.length() < MAX_CHUNK) {
                        last.text += " " + sent;
                        continue;
                    }
                    segs.add(new Segment(sent, rp[0], style, PAUSE_AFTER.get(style), b));
                }
                if (segs.size() > roleStart) {
                    Segment s = segs.get(segs.size() - 1);
                    s.pauseAfter = ROLE_SWITCH_PAUSE + PAUSE_AFTER.get(s.style) / 2;
                }
            }
            if (segs.size() > paraStart) {
                Segment s = segs.get(segs.size() - 1);
                s.pauseAfter = PAUSE_AFTER.get(s.style) + PARAGRAPH_PAUSE;
            }
        }
        return segs;
    }
}
