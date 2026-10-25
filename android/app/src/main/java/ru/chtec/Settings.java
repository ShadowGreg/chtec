package ru.chtec;

import android.content.Context;
import android.content.SharedPreferences;

import ru.chtec.core.EdgeTts;

/** Настройки чтения, сохраняются между запусками. */
public final class Settings {
    public static final String ENGINE_AUTO = "auto"; // Microsoft, а без интернета — голос телефона
    public static final String ENGINE_EDGE = "edge";
    public static final String ENGINE_SYSTEM = "system";

    public String engine = ENGINE_AUTO;
    public String edgeNarrator = EdgeTts.DMITRY;
    public String edgeCharacter = "";   // пусто — тот же голос
    public String systemNarrator = "";  // пусто — голос по умолчанию
    public String systemCharacter = "";
    public String systemEngine = "";    // пакет синтезатора; пусто — как выбрано в настройках телефона
    public int rate = 0;                // %
    public int pitch = 0;               // Гц
    public boolean expressive = true;
    public int fontSize = 19;           // sp
    public boolean follow = true;       // прокручивать текст за чтением
    public boolean roles = true;        // озвучивать персонажей разными голосами

    public static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.engine = p.getString("engine", s.engine);
        s.edgeNarrator = p.getString("edgeNarrator", s.edgeNarrator);
        s.edgeCharacter = p.getString("edgeCharacter", s.edgeCharacter);
        s.systemNarrator = p.getString("systemNarrator", s.systemNarrator);
        s.systemCharacter = p.getString("systemCharacter", s.systemCharacter);
        s.systemEngine = p.getString("systemEngine", s.systemEngine);
        s.rate = p.getInt("rate", s.rate);
        s.pitch = p.getInt("pitch", s.pitch);
        s.expressive = p.getBoolean("expressive", s.expressive);
        s.fontSize = p.getInt("fontSize", s.fontSize);
        s.follow = p.getBoolean("follow", s.follow);
        s.roles = p.getBoolean("roles", s.roles);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("engine", engine)
                .putString("edgeNarrator", edgeNarrator)
                .putString("edgeCharacter", edgeCharacter)
                .putString("systemNarrator", systemNarrator)
                .putString("systemCharacter", systemCharacter)
                .putString("systemEngine", systemEngine)
                .putInt("rate", rate)
                .putInt("pitch", pitch)
                .putBoolean("expressive", expressive)
                .putInt("fontSize", fontSize)
                .putBoolean("follow", follow)
                .putBoolean("roles", roles)
                .apply();
    }

    public Settings copy() {
        Settings s = new Settings();
        s.engine = engine;
        s.edgeNarrator = edgeNarrator;
        s.edgeCharacter = edgeCharacter;
        s.systemNarrator = systemNarrator;
        s.systemCharacter = systemCharacter;
        s.systemEngine = systemEngine;
        s.rate = rate;
        s.pitch = pitch;
        s.expressive = expressive;
        s.fontSize = fontSize;
        s.follow = follow;
        s.roles = roles;
        return s;
    }
}
