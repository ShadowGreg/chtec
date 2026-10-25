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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Закладки и место остановки для каждого документа. Документ узнаётся по содержимому,
 * поэтому тот же файл, открытый снова (хоть из другой папки), продолжится с того же места.
 */
final class Library {
    static final class Bookmark {
        long id;
        int pos;
        String note;
        String snippet;
        long created;
    }

    private static Library instance;
    private final File file;
    private JSONObject root;

    private Library(Context c) {
        file = new File(c.getFilesDir(), "library.json");
        root = load();
    }

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    static String docKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    // ------------------------------------------------------------ место остановки

    synchronized int lastPosition(String key) {
        JSONObject d = root.optJSONObject("docs") != null ? root.optJSONObject("docs").optJSONObject(key) : null;
        return d == null ? -1 : d.optInt("last", -1);
    }

    synchronized void setLastPosition(String key, int pos, String title) {
        if (key == null || pos < 0) return;
        try {
            JSONObject d = doc(key);
            if (d.optInt("last", -1) == pos) return;
            d.put("last", pos);
            if (title != null && !title.isEmpty()) d.put("title", title);
            d.put("updated", System.currentTimeMillis());
            save();
        } catch (JSONException ignored) {
            // не критично
        }
    }

    // ------------------------------------------------------------ закладки

    synchronized List<Bookmark> bookmarks(String key) {
        List<Bookmark> out = new ArrayList<>();
        JSONObject docs = root.optJSONObject("docs");
        JSONObject d = docs == null ? null : docs.optJSONObject(key);
        JSONArray arr = d == null ? null : d.optJSONArray("bookmarks");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Bookmark b = new Bookmark();
            b.id = o.optLong("id");
            b.pos = o.optInt("pos");
            b.note = o.optString("note", "");
            b.snippet = o.optString("snippet", "");
            b.created = o.optLong("created");
            out.add(b);
        }
        Collections.sort(out, new Comparator<Bookmark>() {
            @Override
            public int compare(Bookmark a, Bookmark b) {
                return a.pos != b.pos ? (a.pos < b.pos ? -1 : 1) : 0;
            }
        });
        return out;
    }

    synchronized Bookmark addBookmark(String key, int pos, String snippet, String note, String title) {
        Bookmark b = new Bookmark();
        b.id = System.currentTimeMillis();
        b.pos = pos;
        b.snippet = snippet;
        b.note = note;
        b.created = b.id;
        try {
            JSONObject d = doc(key);
            if (title != null && !title.isEmpty()) d.put("title", title);
            JSONArray arr = d.optJSONArray("bookmarks");
            if (arr == null) {
                arr = new JSONArray();
                d.put("bookmarks", arr);
            }
            arr.put(new JSONObject().put("id", b.id).put("pos", pos).put("note", note)
                    .put("snippet", snippet).put("created", b.created));
            save();
        } catch (JSONException ignored) {
            // не критично
        }
        return b;
    }

    synchronized void updateNote(String key, long id, String note) {
        JSONObject o = find(key, id);
        if (o == null) return;
        try {
            o.put("note", note);
            save();
        } catch (JSONException ignored) {
            // не критично
        }
    }

    synchronized void deleteBookmark(String key, long id) {
        JSONObject docs = root.optJSONObject("docs");
        JSONObject d = docs == null ? null : docs.optJSONObject(key);
        JSONArray arr = d == null ? null : d.optJSONArray("bookmarks");
        if (arr == null) return;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("id") != id) kept.put(o);
        }
        try {
            d.put("bookmarks", kept);
            save();
        } catch (JSONException ignored) {
            // не критично
        }
    }

    // ------------------------------------------------------------ роли (персонажи книги и их голоса)

    static final class Role {
        String name;
        char gender = '?';
        int lines;
        String edgeVoice;   // голос Microsoft
        String phoneVoice;  // голос телефона («пакет|имя», "" — стандартный)
        int pitch, rate;    // сдвиг высоты (Гц) и темпа (%) — чтобы персонажи одним голосом звучали по-разному
        boolean off;        // читать голосом рассказчика
    }

    synchronized Map<String, Role> cast(String key) {
        Map<String, Role> out = new java.util.LinkedHashMap<>();
        JSONObject docs = root.optJSONObject("docs");
        JSONObject d = docs == null ? null : docs.optJSONObject(key);
        JSONArray arr = d == null ? null : d.optJSONArray("cast");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Role r = new Role();
            r.name = o.optString("name");
            String g = o.optString("gender", "?");
            r.gender = g.isEmpty() ? '?' : g.charAt(0);
            r.lines = o.optInt("lines");
            r.edgeVoice = o.has("edge") ? o.optString("edge") : null;
            r.phoneVoice = o.has("phone") ? o.optString("phone") : null;
            r.pitch = o.optInt("pitch");
            r.rate = o.optInt("rate");
            r.off = o.optBoolean("off");
            out.put(r.name, r);
        }
        return out;
    }

    synchronized void saveCast(String key, java.util.Collection<Role> roles) {
        try {
            JSONArray arr = new JSONArray();
            for (Role r : roles) {
                JSONObject o = new JSONObject().put("name", r.name).put("gender", String.valueOf(r.gender))
                        .put("lines", r.lines).put("pitch", r.pitch).put("rate", r.rate).put("off", r.off);
                if (r.edgeVoice != null) o.put("edge", r.edgeVoice);
                if (r.phoneVoice != null) o.put("phone", r.phoneVoice);
                arr.put(o);
            }
            doc(key).put("cast", arr);
            save();
        } catch (JSONException ignored) {
            // не критично
        }
    }

    // ------------------------------------------------------------ хранение

    private JSONObject find(String key, long id) {
        JSONObject docs = root.optJSONObject("docs");
        JSONObject d = docs == null ? null : docs.optJSONObject(key);
        JSONArray arr = d == null ? null : d.optJSONArray("bookmarks");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("id") == id) return o;
        }
        return null;
    }

    private JSONObject doc(String key) throws JSONException {
        JSONObject docs = root.optJSONObject("docs");
        if (docs == null) {
            docs = new JSONObject();
            root.put("docs", docs);
        }
        JSONObject d = docs.optJSONObject(key);
        if (d == null) {
            d = new JSONObject();
            docs.put(key, d);
        }
        return d;
    }

    private JSONObject load() {
        if (!file.exists()) return new JSONObject();
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] b = new byte[(int) file.length()];
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private void save() {
        // Пишем во временный файл и переименовываем — закладки не потеряются, если телефон выключится посреди записи.
        File tmp = new File(file.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) throw new IOException("rename");
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
