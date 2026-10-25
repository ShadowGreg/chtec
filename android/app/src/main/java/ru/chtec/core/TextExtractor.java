package ru.chtec.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Извлечение текста из .doc, .docx, .md, .bin, .txt, .rtf (перенос reader/extract.py). */
public final class TextExtractor {
    private TextExtractor() {}

    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final Charset KOI8 = Charset.forName("KOI8-R");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    public static List<Block> load(byte[] data, String fileName) throws IOException {
        String ext = "";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0) ext = fileName.substring(dot).toLowerCase(Locale.ROOT);

        // Сначала смотрим на содержимое: .bin и переименованные файлы часто оказываются Word-документами.
        if (startsWith(data, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}))
            return plainToBlocks(readDoc(data));
        if (startsWith(data, new byte[]{'P', 'K', 3, 4})) {
            List<Block> docx = readDocx(data);
            if (docx != null) return docx;
        }
        if (startsWith(data, "{\\rtf".getBytes(StandardCharsets.US_ASCII)))
            return plainToBlocks(rtfToText(decodeBytes(data)));

        String text = decodeBytes(data);
        if (ext.equals(".md") || ext.equals(".markdown") || (ext.equals(".bin") && looksLikeMarkdown(text)))
            return markdownToBlocks(text);
        return plainToBlocks(text);
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (data[i] != magic[i]) return false;
        return true;
    }

    // ------------------------------------------------------------ кодировки

    public static String decodeBytes(byte[] data) {
        if (startsWith(data, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}))
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xFE}) || startsWith(data, new byte[]{(byte) 0xFE, (byte) 0xFF}))
            return new String(data, StandardCharsets.UTF_16);

        byte[] sample = head(data, 200_000);
        if (data.length % 2 == 0) {
            for (Charset cs : new Charset[]{StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
                if (plainRatio(new String(sample, cs)) > 0.9) return new String(data, cs);
            }
        }

        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            if (printableRatio(text) > 0.9) return text;
        } catch (CharacterCodingException ignored) {
            // не UTF-8
        }

        Charset[] eightBit = {CP1251, KOI8, StandardCharsets.UTF_8};
        String text = bestRussian(data, eightBit, false);
        int controls = 0;
        for (byte b : sample) {
            int u = b & 0xFF;
            if (u < 9 || (13 < u && u < 32 && u != 27)) controls++;
        }
        if (controls > sample.length * 0.01) {
            // Бинарный файл: текст может лежать внутри и в 8-битной кодировке, и в UTF-16.
            String wide = extractStrings(new String(data, StandardCharsets.UTF_16LE)) + "\n"
                    + extractStrings(new String(data, 1, data.length - 1 - (data.length - 1) % 2, StandardCharsets.UTF_16LE));
            String narrow = bestRussian(data, eightBit, true);
            text = (narrow + "\n" + wide).trim();
        }
        return text;
    }

    private static String bestRussian(byte[] data, Charset[] encodings, boolean strings) {
        String best = null;
        double bestScore = -1e9;
        for (Charset cs : encodings) {
            String t = new String(data, cs);
            if (strings) t = extractStrings(t);
            double s = russianScore(t);
            if (best == null || s > bestScore) {
                best = t;
                bestScore = s;
            }
        }
        return best;
    }

    private static byte[] head(byte[] data, int n) {
        if (data.length <= n) return data;
        byte[] r = new byte[n];
        System.arraycopy(data, 0, r, 0, n);
        return r;
    }

    static double russianScore(String text) {
        int letters = 0, upper = 0, common = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char l = Character.toLowerCase(c);
            if ((l >= 'а' && l <= 'я') || l == 'ё') {
                letters++;
                if ("оеаинтср".indexOf(l) >= 0) common++;
            }
            // В KOI8-R, прочитанном как CP1251 (и наоборот), регистр «переворачивается».
            if (c >= 'А' && c <= 'Я') upper++;
        }
        if (letters == 0) return 0;
        return (double) common / letters - (double) upper / letters;
    }

    private static boolean printable(char c) {
        if (c == '\n' || c == '\r' || c == '\t') return true;
        if (Character.isISOControl(c)) return false;
        int t = Character.getType(c);
        return t != Character.UNASSIGNED && t != Character.PRIVATE_USE && t != Character.SURROGATE
                && t != Character.FORMAT && t != Character.LINE_SEPARATOR && t != Character.PARAGRAPH_SEPARATOR;
    }

    private static double printableRatio(String text) {
        int ok = 0;
        for (int i = 0; i < text.length(); i++) if (printable(text.charAt(i))) ok++;
        return (double) ok / Math.max(text.length(), 1);
    }

    /** Доля символов, обычных для русского/английского текста. */
    private static double plainRatio(String text) {
        int ok = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 'ɐ' && printable(c)) || (c >= 'Ѐ' && c <= 'ӿ')
                    || (c >= '‐' && c <= '…') || "«»№".indexOf(c) >= 0) ok++;
        }
        return (double) ok / Math.max(text.length(), 1);
    }

    private static final Pattern GOOD_WORD = Pattern.compile("[А-ЯЁ]?[а-яё]+|[А-ЯЁ]+|[A-Z]?[a-z]+|[A-Z]+|\\d+");
    private static final Pattern TOKEN = Pattern.compile("[^\\s,.!?;:()«»\"'…—–-]+");
    private static final Pattern RUN = Pattern.compile(
            "[A-Za-zА-Яа-яЁё][0-9A-Za-zА-Яа-яЁё \\t\\n\\r,.!?;:()«»\"'…—–-]{6,}[0-9A-Za-zА-Яа-яЁё.!?…»)\"]");
    private static final Pattern RUN_TAIL = Pattern.compile("([.!?…])\\S{1,3}$");
    private static final Pattern CYR_WORD = Pattern.compile("[А-Яа-яЁё]{3,}");

    /** Отличает настоящий текст от случайных байтов: слова из одного алфавита с нормальным регистром. */
    private static boolean looksLikeWords(String run) {
        int tokens = 0, good = 0;
        Matcher m = TOKEN.matcher(run);
        while (m.find()) {
            tokens++;
            String t = m.group();
            if (t.length() <= 25 && GOOD_WORD.matcher(t).matches()) good++;
        }
        return good >= 2 && good >= tokens * 0.8;
    }

    /** Аналог утилиты strings: оставляет только осмысленные куски текста из бинарных данных. */
    private static String extractStrings(String text) {
        List<String> runs = new ArrayList<>();
        List<String> cyr = new ArrayList<>();
        Matcher m = RUN.matcher(text);
        while (m.find()) {
            String r = m.group();
            if (!looksLikeWords(r)) continue;
            r = RUN_TAIL.matcher(r.trim()).replaceAll("$1");
            runs.add(r);
            if (CYR_WORD.matcher(r).find()) cyr.add(r);
        }
        return join("\n", cyr.isEmpty() ? runs : cyr);
    }

    // ------------------------------------------------------------ Word .docx

    private static List<Block> readDocx(byte[] data) throws IOException {
        String document = null, styles = null;
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            if (e.getName().equals("word/document.xml")) document = readAll(zip);
            else if (e.getName().equals("word/styles.xml")) styles = readAll(zip);
        }
        zip.close();
        if (document == null) return null;

        // Имена стилей: в русском Word стиль «Заголовок 1» часто имеет идентификатор «1».
        Map<String, String> styleNames = new HashMap<>();
        if (styles != null) {
            Matcher sm = Pattern.compile("<w:style\\b[^>]*w:styleId=\"([^\"]+)\"[^>]*>.*?<w:name w:val=\"([^\"]+)\"", Pattern.DOTALL).matcher(styles);
            while (sm.find()) styleNames.put(sm.group(1), sm.group(2).toLowerCase(Locale.ROOT));
        }

        List<Block> blocks = new ArrayList<>();
        // Абзацы и таблицы в порядке документа; строка таблицы читается одной фразой: «ячейка. ячейка».
        Pattern element = Pattern.compile("<w:tbl>.*?</w:tbl>|<w:p[ >].*?</w:p>", Pattern.DOTALL);
        Pattern row = Pattern.compile("<w:tr[ >].*?</w:tr>", Pattern.DOTALL);
        Pattern cell = Pattern.compile("<w:tc>.*?</w:tc>", Pattern.DOTALL);
        Pattern style = Pattern.compile("<w:pStyle w:val=\"([^\"]+)\"");
        Matcher pm = element.matcher(document);
        while (pm.find()) {
            String p = pm.group();
            if (p.startsWith("<w:tbl>")) {
                Matcher rm = row.matcher(p);
                while (rm.find()) {
                    List<String> cells = new ArrayList<>();
                    Matcher cm = cell.matcher(rm.group());
                    while (cm.find()) {
                        String c = docxText(cm.group());
                        if (!c.isEmpty()) cells.add(c);
                    }
                    if (!cells.isEmpty()) blocks.add(new Block("para", join(". ", cells)));
                }
                continue;
            }
            String text = docxText(p);
            if (text.isEmpty()) continue;
            Matcher st = style.matcher(p);
            String styleName = "";
            if (st.find()) {
                String id = st.group(1);
                styleName = styleNames.containsKey(id) ? styleNames.get(id) : id.toLowerCase(Locale.ROOT);
            }
            boolean heading = styleName.startsWith("heading") || styleName.startsWith("заголовок")
                    || styleName.startsWith("title") || looksLikeHeading(text);
            blocks.add(new Block(heading ? "heading" : "para", text));
        }
        return blocks;
    }

    private static final Pattern DOCX_PIECE = Pattern.compile("<w:t(?: [^>]*)?>([^<]*)</w:t>|<w:tab/>|<w:br/>");

    private static String docxText(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = DOCX_PIECE.matcher(xml);
        while (m.find()) sb.append(m.group(1) != null ? xmlUnescape(m.group(1)) : " ");
        return sb.toString().trim();
    }

    private static String readAll(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = zip.read(b)) > 0) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String xmlUnescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1);
            String r;
            if (g.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
            else if (g.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(g.substring(1))));
            else if (g.equals("amp")) r = "&";
            else if (g.equals("lt")) r = "<";
            else if (g.equals("gt")) r = ">";
            else if (g.equals("quot")) r = "\"";
            else r = "'";
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------ Word .doc (97–2003)

    static String readDoc(byte[] data) throws IOException {
        Cfb ole = new Cfb(data);
        byte[] word = ole.open("WordDocument");
        ByteBuffer w = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN);
        int flags = w.getShort(0x0A) & 0xFFFF;
        String tableName = (flags & 0x0200) != 0 ? "1Table" : "0Table";
        if (!ole.exists(tableName)) throw new IOException("Повреждённый .doc: нет потока таблицы");
        byte[] table = ole.open(tableName);

        int fcClx = w.getInt(0x01A2);
        int lcbClx = w.getInt(0x01A6);
        if (fcClx < 0 || lcbClx < 0 || fcClx + lcbClx > table.length) throw new IOException("Повреждённый .doc");
        ByteBuffer clx = ByteBuffer.wrap(table, fcClx, lcbClx).slice().order(ByteOrder.LITTLE_ENDIAN);

        int pos = 0;
        while (pos < lcbClx && clx.get(pos) == 0x01) pos += 3 + (clx.getShort(pos + 1) & 0xFFFF); // Prc
        if (pos >= lcbClx || clx.get(pos) != 0x02) throw new IOException("Не удалось разобрать .doc (нет таблицы фрагментов)");
        int lcb = clx.getInt(pos + 1);
        int plc = pos + 5;
        int n = (lcb - 4) / 12;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cpStart = clx.getInt(plc + 4 * i);
            int cpEnd = clx.getInt(plc + 4 * (i + 1));
            int fc = clx.getInt(plc + 4 * (n + 1) + 8 * i + 2);
            int count = cpEnd - cpStart;
            if (count <= 0) continue;
            if ((fc & 0x40000000) != 0) {
                int start = (fc & ~0x40000000) / 2;
                int len = Math.max(0, Math.min(count, word.length - start));
                sb.append(new String(word, start, len, CP1252));
            } else {
                int len = Math.max(0, Math.min(2 * count, word.length - fc));
                sb.append(new String(word, fc, len, StandardCharsets.UTF_16LE));
            }
        }
        String text = sb.toString();
        // Поля Word: \x13 код \x14 результат \x15 — оставляем только результат.
        text = text.replaceAll("\u0013[^\u0013\u0014\u0015]*\u0014", "");
        text = text.replaceAll("\u0013[^\u0013\u0014\u0015]*\u0015", "");
        text = text.replace("\u0015", "");
        text = text.replace('\r', '\n').replace('\u000b', '\n').replace('\u000c', '\n').replace('\u0007', '\n');
        return text.replaceAll("[\\x00-\\x08\\x0e-\\x1f]", "");
    }

    // ------------------------------------------------------------ RTF

    static String rtfToText(String rtf) {
        Matcher m = Pattern.compile("\\\\'([0-9a-fA-F]{2})").matcher(rtf);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            byte b = (byte) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(new byte[]{b}, CP1251)));
        }
        m.appendTail(sb);
        m = Pattern.compile("\\\\u(-?\\d+)\\??").matcher(sb.toString());
        sb = new StringBuffer();
        while (m.find()) {
            int code = ((Integer.parseInt(m.group(1)) % 65536) + 65536) % 65536;
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
        }
        m.appendTail(sb);
        String text = sb.toString();
        text = text.replaceAll("\\\\pard?(?![a-zA-Z])", "\n");
        text = text.replaceAll("\\{\\\\\\*[^{}]*\\}", "");
        text = text.replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "");
        return text.replace("{", "").replace("}", "");
    }

    // ------------------------------------------------------------ Markdown

    private static boolean looksLikeMarkdown(String text) {
        return Pattern.compile("^(#{1,6} |```)|\\]\\(|\\*\\*\\S", Pattern.MULTILINE).matcher(text).find();
    }

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");
    private static final Pattern MD_DIALOG = Pattern.compile("[,!?…]\\s[-—–]\\s[а-яё]");
    private static final Pattern MD_ITEM = Pattern.compile("^([-*+]|\\d+[.)])\\s+");
    private static final Pattern MD_ITEM_PREFIX = Pattern.compile("^([-*+]|\\d+[.)])\\s+(\\[[ xX]\\]\\s+)?");

    public static List<Block> markdownToBlocks(String md) {
        md = Pattern.compile("```.*?```", Pattern.DOTALL).matcher(md).replaceAll("");
        md = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(md).replaceAll("");
        List<Block> blocks = new ArrayList<>();
        List<String> para = new ArrayList<>();
        for (String raw : md.split("\r\n|\r|\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("[-*_=]{3,}") || line.matches("\\|?[\\s:|-]+\\|?")) {
                flush(blocks, para);
                continue;
            }
            Matcher m = MD_HEADING.matcher(line);
            if (m.find()) {
                flush(blocks, para);
                blocks.add(new Block("heading", mdInline(m.group(2))));
                continue;
            }
            if (line.startsWith("- ") && MD_DIALOG.matcher(line).find()) {
                flush(blocks, para); // «- Привет, - сказал он.» — реплика диалога, а не список
                blocks.add(new Block("para", "— " + mdInline(line.substring(2))));
                continue;
            }
            if (MD_ITEM.matcher(line).find()) {
                flush(blocks, para);
                blocks.add(new Block("para", mdInline(MD_ITEM_PREFIX.matcher(line).replaceFirst(""))));
                continue;
            }
            if (line.startsWith("|")) {
                flush(blocks, para);
                List<String> cells = new ArrayList<>();
                for (String c : line.replaceAll("^\\|+|\\|+$", "").split("\\|"))
                    if (!c.trim().isEmpty()) cells.add(mdInline(c));
                blocks.add(new Block("para", join(". ", cells)));
                continue;
            }
            para.add(mdInline(line.replaceFirst("^>\\s?", "")));
        }
        flush(blocks, para);
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) if (!b.text.trim().isEmpty()) out.add(b);
        return out;
    }

    private static void flush(List<Block> blocks, List<String> para) {
        if (!para.isEmpty()) {
            blocks.add(new Block("para", join(" ", para)));
            para.clear();
        }
    }

    private static String mdInline(String s) {
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("`([^`]*)`", "$1");
        s = s.replaceAll("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1", "$2");
        s = s.replaceAll("<[^>]+>", "");
        return s.trim();
    }

    // ------------------------------------------------------------ обычный текст

    private static final Pattern HEADING_END = Pattern.compile("[.,;:!?…»\"]$");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…][»\"]?$");

    static boolean looksLikeHeading(String line) {
        return line.length() < 70 && !HEADING_END.matcher(line).find()
                && !(line.startsWith("—") || line.startsWith("–") || line.startsWith("-"));
    }

    /** Делит текст на абзацы и заголовки; запоминает, где каждый абзац лежит в исходном тексте. */
    public static List<Block> plainToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        // Разбиваем на строки, запоминая смещения (\r\n и \r тоже считаются переводом строки).
        List<String> lines = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        int i = 0, n = text.length();
        while (i <= n) {
            int j = i;
            while (j < n && text.charAt(j) != '\n' && text.charAt(j) != '\r') j++;
            String raw = text.substring(i, j);
            int lead = 0;
            while (lead < raw.length() && Character.isWhitespace(raw.charAt(lead))) lead++;
            String line = raw.trim();
            spans.add(new int[]{i + lead, i + lead + line.length()});
            lines.add(line);
            if (j < n && text.charAt(j) == '\r' && j + 1 < n && text.charAt(j + 1) == '\n') j++;
            i = j + 1;
        }

        List<String> buf = new ArrayList<>();
        int bufStart = -1, bufEnd = -1;
        for (int k = 0; k < lines.size(); k++) {
            String line = lines.get(k);
            if (line.isEmpty()) {
                if (!buf.isEmpty()) blocks.add(new Block("para", join(" ", buf), bufStart, bufEnd));
                buf.clear();
                continue;
            }
            boolean nextEmpty = k + 1 >= lines.size() || lines.get(k + 1).isEmpty();
            // Короткая строка без точки в конце, окружённая пустыми строками, — скорее заголовок.
            if (buf.isEmpty() && nextEmpty && looksLikeHeading(line)) {
                blocks.add(new Block("heading", line, spans.get(k)[0], spans.get(k)[1]));
                continue;
            }
            // Строка, начинающаяся с тире, — новая реплика/абзац.
            if ((line.startsWith("—") || line.startsWith("–") || line.startsWith("- ")) && !buf.isEmpty()) {
                blocks.add(new Block("para", join(" ", buf), bufStart, bufEnd));
                buf.clear();
            }
            if (buf.isEmpty()) bufStart = spans.get(k)[0];
            buf.add(line);
            bufEnd = spans.get(k)[1];
            // Строка закончилась концом предложения — считаем это концом абзаца.
            if (SENTENCE_END.matcher(line).find()) {
                blocks.add(new Block("para", join(" ", buf), bufStart, bufEnd));
                buf.clear();
            }
        }
        if (!buf.isEmpty()) blocks.add(new Block("para", join(" ", buf), bufStart, bufEnd));
        return blocks;
    }

    static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
