package ru.chtec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ru.chtec.core.Prepare;
import ru.chtec.core.Segment;

/**
 * Все синтезаторы речи телефона сразу: Google, VoxSherpa, RHVoice и любые другие установленные.
 * Список берётся у Android каждый раз заново — только что установленный синтезатор виден сразу.
 * Голос записывается как «пакет|имя», поэтому рассказчик и персонажи могут быть из разных синтезаторов.
 */
final class PhoneVoices {
    static final String GOOGLE = "com.google.android.tts";
    static final String SAMSUNG = "com.samsung.SMT";
    private static final Locale RU = new Locale("ru", "RU");

    interface Callback {
        void onChanged();

        void log(String msg);
    }

    static final class Engine {
        final String pkg;
        final String label;
        TextToSpeech tts;
        volatile int status; // 0 — подключаюсь, 1 — готов, -1 — не отвечает
        final Map<String, Voice> voices = new LinkedHashMap<>();
        boolean russianLanguage; // синтезатор умеет русский (даже без отдельных голосов)

        Engine(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        int russianVoices() {
            int n = 0;
            for (Voice v : voices.values()) if (isRussian(v)) n++;
            return n;
        }
    }

    /** Голос для списка выбора. */
    static final class Item {
        String key;      // «пакет|имя»
        String label;
        boolean russian;
    }

    private final Context ctx;
    private final Handler main;
    private final Callback cb;
    private final Map<String, Engine> engines = new LinkedHashMap<>();
    private final Map<String, CountDownLatch> waits = new ConcurrentHashMap<>();
    private final Set<String> done = Collections.synchronizedSet(new HashSet<String>());
    private int counter;
    private volatile String hiddenDefault;

    PhoneVoices(Context ctx, Handler main, Callback cb) {
        this.ctx = ctx;
        this.main = main;
        this.cb = cb;
    }

    // ------------------------------------------------------------ подключение

    String phoneDefault() {
        try {
            return android.provider.Settings.Secure.getString(ctx.getContentResolver(), "tts_default_synth");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Установленные синтезаторы (пакет -> название) — прямо из Android, без кэша. */
    Map<String, String> installed() {
        Map<String, String> out = new LinkedHashMap<>();
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo ri : list) {
            if (ri.serviceInfo == null) continue;
            CharSequence l = ri.loadLabel(pm);
            out.put(ri.serviceInfo.packageName, l != null ? l.toString() : ri.serviceInfo.packageName);
        }
        return out;
    }

    /**
     * Обновляет список синтезаторов. Подключается к основному и к тем, чьи голоса выбраны;
     * all = true — ко всем (для списка голосов в настройках); reconnect — заново перечитать голоса.
     */
    synchronized void refresh(Settings s, boolean all, boolean reconnect) {
        Map<String, String> inst = installed();
        String def = phoneDefault();
        hiddenDefault = def != null && !def.isEmpty() && !inst.containsKey(def) ? def : null;
        for (String pkg : new ArrayList<>(engines.keySet())) {
            if (!inst.containsKey(pkg)) {
                Engine e = engines.remove(pkg);
                if (e.tts != null) e.tts.shutdown();
            }
        }
        Set<String> wanted = new HashSet<>();
        if (all) wanted.addAll(inst.keySet());
        String mainPkg = mainPkg(s, inst);
        if (mainPkg != null) wanted.add(mainPkg);
        for (String key : new String[]{s.systemNarrator, s.systemCharacter}) {
            String pkg = pkgOf(key, mainPkg);
            if (pkg != null && inst.containsKey(pkg)) wanted.add(pkg);
        }
        for (Map.Entry<String, String> en : inst.entrySet()) {
            String pkg = en.getKey();
            if (!wanted.contains(pkg)) continue;
            Engine old = engines.get(pkg);
            if (old != null && !reconnect && old.status != -1) continue;
            if (old != null && old.tts != null) old.tts.shutdown();
            connect(new Engine(pkg, en.getValue()));
        }
        cb.onChanged();
    }

    private void connect(final Engine e) {
        engines.put(e.pkg, e);
        final TextToSpeech[] self = new TextToSpeech[1];
        self[0] = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                // Позже: при ошибке Android зовёт onInit ещё внутри конструктора.
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onConnected(e, self[0], status);
                    }
                });
            }
        }, e.pkg);
        e.tts = self[0];
    }

    private void onConnected(Engine e, TextToSpeech t, int status) {
        synchronized (this) {
            if (engines.get(e.pkg) != e || e.tts != t) return; // уже переподключились
        }
        if (status != TextToSpeech.SUCCESS) {
            e.status = -1;
            cb.log("Синтезатор " + e.label + ": не удалось подключиться");
            cb.onChanged();
            return;
        }
        try {
            int r = t.isLanguageAvailable(RU);
            e.russianLanguage = r >= TextToSpeech.LANG_AVAILABLE;
        } catch (RuntimeException ignored) {
            e.russianLanguage = false;
        }
        e.voices.clear();
        try {
            Set<Voice> vs = t.getVoices();
            if (vs != null) {
                List<Voice> list = new ArrayList<>(vs);
                Collections.sort(list, new Comparator<Voice>() {
                    @Override
                    public int compare(Voice a, Voice b) {
                        boolean ra = isRussian(a), rb = isRussian(b);
                        if (ra != rb) return ra ? -1 : 1;
                        return a.getName().compareTo(b.getName());
                    }
                });
                for (Voice v : list) {
                    boolean notInstalled = v.getFeatures() != null
                            && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                    if (!notInstalled) e.voices.put(v.getName(), v);
                }
            }
        } catch (RuntimeException ex) {
            cb.log("Синтезатор " + e.label + ": не отдал список голосов (" + ex.getClass().getSimpleName() + ")");
        }
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                done.add(id);
                release(id);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String id) {
                release(id);
            }

            @Override
            public void onError(String id, int code) {
                release(id);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                release(id);
            }

            private void release(String id) {
                CountDownLatch l = waits.remove(id);
                if (l != null) l.countDown();
            }
        });
        e.status = 1;
        cb.onChanged();
    }

    synchronized void shutdown() {
        for (Engine e : engines.values()) if (e.tts != null) e.tts.shutdown();
        engines.clear();
    }

    synchronized void stopAll() {
        for (Engine e : engines.values()) {
            try {
                if (e.status == 1) e.tts.stop();
            } catch (RuntimeException ignored) {
                // синтезатор отключился
            }
        }
        for (CountDownLatch l : waits.values()) l.countDown();
    }

    // ------------------------------------------------------------ что показывать

    static boolean isRussian(Voice v) {
        Locale l = v.getLocale();
        if (l == null) return false;
        String lang = l.getLanguage();
        if ("ru".equals(lang) || "rus".equalsIgnoreCase(lang)) return true;
        try {
            return "rus".equalsIgnoreCase(l.getISO3Language());
        } catch (RuntimeException e) {
            return false;
        }
    }

    synchronized List<Engine> engines() {
        return new ArrayList<>(engines.values());
    }

    String label(String pkg) {
        Engine e;
        synchronized (this) {
            e = engines.get(pkg);
        }
        if (e != null) return e.label;
        String l = installed().get(pkg);
        if (l != null) return l;
        if (SAMSUNG.equals(pkg)) return "Samsung TTS";
        if (GOOGLE.equals(pkg)) return "Синтезатор речи Google";
        return pkg == null ? "—" : pkg;
    }

    /** Синтезатор, выбранный основным в телефоне, но закрытый для приложений (Samsung на One UI 7+), или null. */
    String hiddenDefaultLabel() {
        String h = hiddenDefault;
        return h == null ? null : label(h);
    }

    /** Все голоса всех подключённых синтезаторов: сначала русские. */
    synchronized List<Item> voices() {
        List<Item> ru = new ArrayList<>(), other = new ArrayList<>();
        Locale display = new Locale("ru");
        for (Engine e : engines.values()) {
            if (e.status != 1) continue;
            if (e.voices.isEmpty() && e.russianLanguage) {
                Item it = new Item();
                it.key = e.pkg + "|";
                it.label = e.label + "\nстандартный голос";
                it.russian = true;
                ru.add(it);
            }
            for (Voice v : e.voices.values()) {
                Item it = new Item();
                it.key = e.pkg + "|" + v.getName();
                it.russian = isRussian(v);
                String kind = v.isNetworkConnectionRequired() ? "нужен интернет" : "офлайн";
                String lang = v.getLocale() == null ? "?" : v.getLocale().getDisplayLanguage(display);
                // Две строки: синтезатор, затем голос — так длинное название читается целиком.
                it.label = e.label + "\n" + v.getName() + " (" + (it.russian ? "" : lang + ", ") + kind + ")";
                (it.russian ? ru : other).add(it);
            }
        }
        ru.addAll(other);
        return ru;
    }

    // ------------------------------------------------------------ выбор синтезатора и голоса

    private String mainPkg(Settings s, Map<String, String> inst) {
        if (s.systemEngine != null && !s.systemEngine.isEmpty() && inst.containsKey(s.systemEngine)) return s.systemEngine;
        String def = phoneDefault();
        if (def != null && inst.containsKey(def)) return def;
        if (inst.containsKey(GOOGLE)) return GOOGLE;
        return inst.isEmpty() ? null : inst.keySet().iterator().next();
    }

    /** Синтезатор для «стандартного голоса телефона». */
    String mainPkg(Settings s) {
        return mainPkg(s, installed());
    }

    private static String pkgOf(String key, String mainPkg) {
        if (key == null || key.isEmpty()) return null;
        int i = key.indexOf('|');
        return i < 0 ? mainPkg : key.substring(0, i); // старые настройки: только имя голоса
    }

    private static String nameOf(String key) {
        int i = key.indexOf('|');
        return i < 0 ? key : key.substring(i + 1);
    }

    private synchronized Engine engine(String pkg) {
        return pkg == null ? null : engines.get(pkg);
    }

    /** Готов ли голос рассказчика (и синтезатор для него). */
    boolean isReady(Settings s) {
        Engine e = engine(pkgOf(s.systemNarrator, mainPkg(s)));
        if (e == null) e = engine(mainPkg(s));
        return e != null && e.status == 1;
    }

    /** Почему голос телефона не готов (или null). */
    String problem(Settings s) {
        String mp = mainPkg(s);
        if (mp == null) return "В телефоне нет ни одного синтезатора речи. Установите «Синтезатор речи Google» из Play Маркета.";
        Engine e = engine(pkgOf(s.systemNarrator, mp));
        if (e == null) e = engine(mp);
        if (e == null || e.status == 0) return "Синтезатор «" + label(mp) + "» ещё подключается — попробуйте через пару секунд.";
        if (e.status == -1) return "Синтезатор «" + e.label + "» не отвечает. Выберите голос другого синтезатора.";
        if (!e.russianLanguage && e.russianVoices() == 0) {
            return "В синтезаторе «" + e.label + "» нет русского голоса. Скачайте русский голос в нём самом "
                    + "или выберите голос другого синтезатора.";
        }
        return null;
    }

    // ------------------------------------------------------------ озвучка

    /** Озвучивает фрагмент в WAV выбранным голосом; при неудаче — голосом по умолчанию, затем основным синтезатором. */
    boolean synth(Segment seg, Settings s, File out, Online online) throws InterruptedException {
        int[] p = Prepare.PROSODY.get(seg.style);
        int dRate = p[0], dPitch = p[1], dVol = p[2];
        String key = s.systemNarrator;
        if (seg.phoneVoice != null) {
            // У персонажа свой голос (роль) — на всю книгу.
            key = seg.phoneVoice;
            dRate += seg.rateShift;
            dPitch += seg.pitchShift;
        } else if (seg.role.equals("character")) {
            if (!s.systemCharacter.isEmpty()) {
                key = s.systemCharacter;
            } else {
                dRate += 3; // тот же голос, но реплики персонажей чуть живее и выше
                dPitch += 3;
            }
        }
        float rate = clamp((100f + s.rate + dRate) / 100f, 0.3f, 3f);
        float pitch = clamp(1f + (s.pitch + dPitch) / 60f, 0.5f, 2f);
        float vol = clamp(0.85f * (100f + dVol) / 100f, 0f, 1f);

        String mp = mainPkg(s);
        Engine e = engine(pkgOf(key, mp));
        Voice v = null;
        if (e != null && e.status == 1 && key != null && !key.isEmpty()) {
            v = e.voices.get(nameOf(key));
            if (v != null && v.isNetworkConnectionRequired() && !online.now()) {
                // Голосу нужен интернет, а его нет — офлайн-вариант того же голоса, если есть.
                Voice local = e.voices.get(v.getName().replace("-network", "-local"));
                v = local != null && !local.isNetworkConnectionRequired() ? local : null;
            }
        }
        if (e == null || e.status != 1) e = engine(mp);
        if (e == null || e.status != 1) return false;

        if (synthWith(e, v, seg.text, rate, pitch, vol, out, 40)) return true;
        cb.log(e.label + (v != null ? " / " + v.getName() : "") + ": не прочитал фразу, повторяю голосом по умолчанию");
        if (v != null && synthWith(e, null, seg.text, rate, pitch, vol, out, 40)) return true;
        Engine m = engine(mp);
        if (m != null && m != e && m.status == 1) {
            cb.log(e.label + ": не справился, читаю основным синтезатором " + m.label);
            return synthWith(m, null, seg.text, rate, pitch, vol, out, 40);
        }
        return false;
    }

    interface Online {
        boolean now();
    }

    boolean synthWith(Engine e, Voice v, String text, float rate, float pitch, float vol, File out, int timeoutSec)
            throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);
        String id;
        synchronized (this) {
            id = "u" + (++counter);
        }
        CountDownLatch latch = new CountDownLatch(1);
        waits.put(id, latch);
        try {
            int r;
            synchronized (e) {
                if (e.tts == null) return false;
                if (v != null) e.tts.setVoice(v);
                else e.tts.setLanguage(RU);
                e.tts.setSpeechRate(rate);
                e.tts.setPitch(pitch);
                r = e.tts.synthesizeToFile(text, params, out, id);
            }
            if (r == TextToSpeech.ERROR) return false;
            latch.await(timeoutSec, TimeUnit.SECONDS);
            return done.remove(id) && out.length() > 44;
        } catch (RuntimeException ex) {
            cb.log(e.label + ": ошибка синтеза — " + ex.getClass().getSimpleName());
            return false;
        } finally {
            waits.remove(id);
        }
    }

    /** «▶ Проба»: произнести фразу голосом по ключу (пусто — стандартный голос). */
    boolean speak(String key, Settings s, String text, int pitchShift, int rateShift) {
        String mp = mainPkg(s);
        Engine e = engine(key == null || key.isEmpty() ? mp : pkgOf(key, mp));
        if (e == null || e.status != 1) return false;
        Voice v = key == null || key.isEmpty() ? null : e.voices.get(nameOf(key));
        stopAll();
        synchronized (e) {
            if (v != null) e.tts.setVoice(v);
            else e.tts.setLanguage(RU);
            e.tts.setSpeechRate(clamp((100f + s.rate + rateShift) / 100f, 0.3f, 3f));
            e.tts.setPitch(clamp(1f + (s.pitch + pitchShift) / 60f, 0.5f, 2f));
            return e.tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview") != TextToSpeech.ERROR;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
