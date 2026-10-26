package ru.chtec;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ru.chtec.core.EdgeTts;

/**
 * Чтецы — готовые наборы голоса рассказчика: голос Microsoft, голос телефона, темп и высота.
 * Можно завести несколько, послушать каждого и выбрать, кто читает.
 */
final class Readers {
    private Readers() {}

    static final class Reader {
        String id;
        String name;
        String edge = EdgeTts.DMITRY; // голос Microsoft
        String phone = "";             // голос телефона («пакет|имя», "" — стандартный)
        int rate, pitch;               // темп %, высота Гц
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("readers", Context.MODE_PRIVATE);
    }

    /** Чтецы; при первом запуске — два: «Дмитрий» (с текущими настройками) и «Светлана». */
    static List<Reader> load(Context c, Settings s) {
        List<Reader> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Reader r = new Reader();
                r.id = o.getString("id");
                r.name = o.optString("name", "Чтец");
                r.edge = o.optString("edge", EdgeTts.DMITRY);
                r.phone = o.optString("phone", "");
                r.rate = o.optInt("rate");
                r.pitch = o.optInt("pitch");
                out.add(r);
            }
        } catch (JSONException ignored) {
            // повреждено — начнём заново
        }
        if (out.isEmpty()) {
            Reader a = new Reader();
            a.id = "r1";
            a.name = EdgeTts.SVETLANA.equals(s.edgeNarrator) ? "Светлана" : "Дмитрий";
            a.edge = s.edgeNarrator;
            a.phone = s.systemNarrator;
            a.rate = s.rate;
            a.pitch = s.pitch;
            Reader b = new Reader();
            b.id = "r2";
            b.name = EdgeTts.SVETLANA.equals(a.edge) ? "Дмитрий" : "Светлана";
            b.edge = EdgeTts.SVETLANA.equals(a.edge) ? EdgeTts.DMITRY : EdgeTts.SVETLANA;
            out.add(a);
            out.add(b);
            save(c, out, a.id);
        }
        return out;
    }

    static String activeId(Context c) {
        return prefs(c).getString("active", "r1");
    }

    static void save(Context c, List<Reader> readers, String activeId) {
        JSONArray arr = new JSONArray();
        try {
            for (Reader r : readers) {
                arr.put(new JSONObject().put("id", r.id).put("name", r.name).put("edge", r.edge)
                        .put("phone", r.phone).put("rate", r.rate).put("pitch", r.pitch));
            }
        } catch (JSONException ignored) {
            // не бывает
        }
        prefs(c).edit().putString("list", arr.toString()).putString("active", activeId).apply();
    }

    /** Выбранный чтец становится рассказчиком. */
    static void apply(Reader r, Settings s) {
        s.edgeNarrator = r.edge;
        s.systemNarrator = r.phone;
        s.rate = r.rate;
        s.pitch = r.pitch;
    }

    static String describe(Reader r, List<PhoneVoices.Item> phone) {
        String edge = EdgeTts.SVETLANA.equals(r.edge) ? "Светлана" : "Дмитрий";
        String ph = "стандартный голос";
        for (PhoneVoices.Item it : phone) if (it.key.equals(r.phone)) ph = it.label;
        StringBuilder sb = new StringBuilder("Microsoft: ").append(edge).append("\nТелефон: ").append(ph);
        if (r.rate != 0) sb.append("\nТемп ").append(r.rate > 0 ? "+" : "").append(r.rate).append('%');
        if (r.pitch != 0) sb.append(r.rate != 0 ? ", высота " : "\nВысота ").append(r.pitch > 0 ? "+" : "").append(r.pitch);
        return sb.toString();
    }
}
