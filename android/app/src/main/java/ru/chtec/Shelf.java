package ru.chtec;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Библиотека: найденные и добавленные книги, полки «Читаю», «Прочитано», «Неинтересные» и свои сборки. */
final class Shelf {
    static final String READING = "reading";
    static final String DONE = "done";
    static final String BORING = "boring";
    static final String[] BUILT_IN = {READING, DONE, BORING};
    static final String[] BUILT_IN_NAMES = {"Читаю", "Прочитано", "Неинтересные"};

    static final class Book {
        String id;       // путь к файлу или адрес content://
        String path;     // если найден на телефоне
        String uri;      // если добавлен через «Открыть файл» / «Поделиться»
        String name;
        long size, modified, added, opened;
        boolean isNew;
        String docKey;   // отпечаток текста — по нему закладки и место остановки
        int textLength;
        Set<String> cols = new HashSet<>();

        String ext() {
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        }

        String title() {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
    }

    static final class Col {
        final String id, name;

        Col(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static Shelf instance;
    private final File file;
    private final Map<String, Book> books = new LinkedHashMap<>();
    private final List<Col> custom = new ArrayList<>();

    private Shelf(Context c) {
        file = new File(c.getFilesDir(), "shelf.json");
        load();
    }

    static synchronized Shelf get(Context c) {
        if (instance == null) instance = new Shelf(c.getApplicationContext());
        return instance;
    }

    // ------------------------------------------------------------ книги

    synchronized List<Book> all() {
        return new ArrayList<>(books.values());
    }

    synchronized Book get(String id) {
        return books.get(id);
    }

    /** Итог поиска на телефоне: новые файлы помечаются «новыми», исчезнувшие — убираются. Возвращает число новых. */
    synchronized int applyScan(List<File> found) {
        Set<String> seen = new HashSet<>();
        int fresh = 0;
        long now = System.currentTimeMillis();
        for (File f : found) {
            String id = f.getAbsolutePath();
            seen.add(id);
            Book b = books.get(id);
            if (b == null) {
                b = new Book();
                b.id = id;
                b.path = id;
                b.name = f.getName();
                b.added = now;
                b.isNew = true;
                books.put(id, b);
                fresh++;
            } else if (b.modified != f.lastModified()) {
                b.docKey = null; // файл изменился — текст перечитаем
            }
            b.size = f.length();
            b.modified = f.lastModified();
        }
        for (Iterator<Book> it = books.values().iterator(); it.hasNext(); ) {
            Book b = it.next();
            if (b.path != null && !seen.contains(b.path) && !new File(b.path).exists()) it.remove();
        }
        save();
        return fresh;
    }

    /** Файл, открытый вручную или из другого приложения. */
    synchronized Book addUri(String uri, String path, String name, long size) {
        String id = path != null ? path : uri;
        Book b = books.get(id);
        if (b == null) {
            b = new Book();
            b.id = id;
            b.path = path;
            b.uri = path == null ? uri : null;
            b.added = System.currentTimeMillis();
            books.put(id, b);
        }
        b.name = name;
        if (size > 0) b.size = size;
        save();
        return b;
    }

    synchronized void setOpened(String id, String docKey, int textLength) {
        Book b = books.get(id);
        if (b == null) return;
        b.opened = System.currentTimeMillis();
        b.isNew = false;
        b.docKey = docKey;
        b.textLength = textLength;
        save();
    }

    synchronized void setInCollection(String id, String col, boolean on) {
        Book b = books.get(id);
        if (b == null) return;
        if (on) {
            b.cols.add(col);
            if (col.equals(DONE)) b.cols.remove(READING);
            if (col.equals(READING)) b.cols.remove(DONE);
        } else {
            b.cols.remove(col);
        }
        b.isNew = false;
        save();
    }

    /** Начали слушать — книга на полке «Читаю». */
    synchronized void markReading(String id) {
        Book b = books.get(id);
        if (b == null || b.cols.contains(READING)) return;
        b.cols.add(READING);
        b.cols.remove(DONE);
        save();
    }

    /** Дослушали до конца — на полку «Прочитано». */
    synchronized void markFinished(String docKey) {
        if (docKey == null) return;
        boolean changed = false;
        for (Book b : books.values()) {
            if (docKey.equals(b.docKey)) {
                b.cols.remove(READING);
                b.cols.add(DONE);
                changed = true;
            }
        }
        if (changed) save();
    }

    synchronized void remove(String id) {
        books.remove(id);
        save();
    }

    synchronized Book lastOpened() {
        Book best = null;
        for (Book b : books.values()) if (b.opened > 0 && (best == null || b.opened > best.opened)) best = b;
        return best;
    }

    // ------------------------------------------------------------ сборки

    synchronized List<Col> collections() {
        List<Col> out = new ArrayList<>();
        for (int i = 0; i < BUILT_IN.length; i++) out.add(new Col(BUILT_IN[i], BUILT_IN_NAMES[i]));
        out.addAll(custom);
        return out;
    }

    synchronized List<Col> customCollections() {
        return new ArrayList<>(custom);
    }

    synchronized Col addCollection(String name) {
        Col c = new Col("c" + System.currentTimeMillis(), name);
        custom.add(c);
        save();
        return c;
    }

    synchronized void renameCollection(String id, String name) {
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id.equals(id)) custom.set(i, new Col(id, name));
        }
        save();
    }

    synchronized void deleteCollection(String id) {
        for (Iterator<Col> it = custom.iterator(); it.hasNext(); ) if (it.next().id.equals(id)) it.remove();
        for (Book b : books.values()) b.cols.remove(id);
        save();
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("books");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Book b = new Book();
                    b.id = o.getString("id");
                    b.path = o.has("path") ? o.getString("path") : null;
                    b.uri = o.has("uri") ? o.getString("uri") : null;
                    b.name = o.optString("name", "файл");
                    b.size = o.optLong("size");
                    b.modified = o.optLong("modified");
                    b.added = o.optLong("added");
                    b.opened = o.optLong("opened");
                    b.isNew = o.optBoolean("new");
                    b.docKey = o.has("docKey") ? o.getString("docKey") : null;
                    b.textLength = o.optInt("textLength");
                    JSONArray cols = o.optJSONArray("cols");
                    if (cols != null) for (int k = 0; k < cols.length(); k++) b.cols.add(cols.getString(k));
                    books.put(b.id, b);
                }
            }
            JSONArray cols = root.optJSONArray("collections");
            if (cols != null) {
                for (int i = 0; i < cols.length(); i++) {
                    JSONObject o = cols.getJSONObject(i);
                    custom.add(new Col(o.getString("id"), o.getString("name")));
                }
            }
        } catch (IOException | JSONException ignored) {
            // повреждённый файл — начнём заново
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Book b : books.values()) {
                JSONObject o = new JSONObject().put("id", b.id).put("name", b.name).put("size", b.size)
                        .put("modified", b.modified).put("added", b.added).put("opened", b.opened)
                        .put("new", b.isNew).put("textLength", b.textLength).put("cols", toArray(b.cols));
                if (b.path != null) o.put("path", b.path);
                if (b.uri != null) o.put("uri", b.uri);
                if (b.docKey != null) o.put("docKey", b.docKey);
                arr.put(o);
            }
            JSONArray cols = new JSONArray();
            for (Col c : custom) cols.put(new JSONObject().put("id", c.id).put("name", c.name));
            byte[] data = new JSONObject().put("books", arr).put("collections", cols).toString()
                    .getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        } catch (IOException | JSONException ignored) {
            // не критично
        }
    }

    private static JSONArray toArray(Collection<String> items) {
        JSONArray a = new JSONArray();
        for (String s : items) a.put(s);
        return a;
    }
}
