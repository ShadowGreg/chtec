package ru.chtec;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.chtec.core.TextExtractor;

/** Поиск на телефоне всех файлов, которые можно прочитать вслух. */
final class Scanner {
    private Scanner() {}

    interface Progress {
        void onProgress(int folders, int found);
    }

    static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "doc", "docx", "md", "markdown", "txt", "rtf", "bin"));
    private static final long MAX_SIZE = 60L * 1024 * 1024;
    private static final int MAX_DEPTH = 14;

    static List<File> scan(Context c, Progress progress, AtomicBoolean cancel) {
        List<File> found = new ArrayList<>();
        int[] folders = {0};
        for (File root : roots(c)) walk(root, 0, found, folders, progress, cancel);
        return found;
    }

    /** Внутренняя память и карты памяти. */
    private static Set<File> roots(Context c) {
        Set<File> roots = new LinkedHashSet<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] dirs = c.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int i = p.indexOf("/Android/data/");
                if (i > 0) roots.add(new File(p.substring(0, i)));
            }
        }
        return roots;
    }

    private static void walk(File dir, int depth, List<File> found, int[] folders, Progress progress, AtomicBoolean cancel) {
        if (cancel.get() || depth > MAX_DEPTH) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        folders[0]++;
        if (folders[0] % 25 == 0) progress.onProgress(folders[0], found.size());
        for (File f : list) {
            if (cancel.get()) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                String p = f.getAbsolutePath();
                // Служебные папки приложений: там нет книг, зато тысячи файлов.
                if (p.endsWith("/Android/data") || p.endsWith("/Android/obb")) continue;
                walk(f, depth + 1, found, folders, progress, cancel);
            } else if (readable(f)) {
                found.add(f);
            }
        }
    }

    private static boolean readable(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(ext)) return false;
        long size = f.length();
        if (size < 64 || size > MAX_SIZE) return false;
        // .bin бывают чем угодно (прошивки, кэши) — берём только те, где действительно есть русский текст.
        return !ext.equals("bin") || hasRussianText(f);
    }

    private static boolean hasRussianText(File f) {
        try {
            byte[] head = new byte[(int) Math.min(f.length(), 256 * 1024)];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < head.length) {
                    int n = in.read(head, off, head.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            if (head.length > 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF) return true; // Word .doc
            if (head.length > 4 && head[0] == 'P' && head[1] == 'K') return true;                    // Word .docx
            String text = TextExtractor.decodeBytes(head);
            int cyr = 0;
            for (int i = 0; i < text.length() && cyr < 200; i++) {
                char ch = text.charAt(i);
                if ((ch >= 'а' && ch <= 'я') || (ch >= 'А' && ch <= 'Я') || ch == 'ё' || ch == 'Ё') cyr++;
            }
            return cyr >= 200;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
