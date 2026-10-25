package ru.chtec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ru.chtec.core.EdgeTts;
import ru.chtec.core.Prepare;
import ru.chtec.core.Segment;
import ru.chtec.core.Timing;
import ru.chtec.core.Wav;

/**
 * Фоновое чтение как аудиодорожка: фрагменты заранее озвучиваются в файлы и играют подряд без зазоров
 * (MediaPlayer.setNextMediaPlayer), поэтому пауза и продолжение — точно с того же места.
 * Управление из уведомления, с экрана блокировки и кнопками наушников (MediaSession).
 * Озвучка голосами Microsoft сохраняется на телефоне — повторно и заранее подготовленное играет без интернета.
 */
public class ReaderService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final int STOPPED = 0, PLAYING = 1, PAUSED = 2, SAVING = 3, PREPARING = 4;

    static final String ACTION_TOGGLE = "ru.chtec.TOGGLE";
    static final String ACTION_STOP = "ru.chtec.STOP";
    static final String ACTION_NEXT = "ru.chtec.NEXT";
    static final String ACTION_PREV = "ru.chtec.PREV";
    private static final String CHANNEL = "reading";
    private static final int NOTIFY_ID = 1;
    private static final Locale RU = new Locale("ru", "RU");
    private static final int AHEAD_EDGE = 10;               // сколько фраз готовить заранее (Microsoft — с запасом)
    private static final int AHEAD_PHONE = 4;
    private static final int EDGE_PARALLEL = 2;              // одновременных запросов к Microsoft
    private static final long CACHE_LIMIT = 1024L * 1024 * 1024;

    public interface Listener {
        void onState(int state, boolean buffering);

        void onPosition(int segment, int word);

        void onTaskProgress(int done, int total);

        void onMessage(String text, boolean error);

        void onVoicesReady();
    }

    public class LocalBinder extends Binder {
        ReaderService service() {
            return ReaderService.this;
        }
    }

    /** Озвученный фрагмент: файл (вместе с паузой после него) и время начала каждого слова. */
    private static final class Clip {
        final File file;
        final int[] wordMs;
        final boolean temp;

        Clip(File file, int[] wordMs, boolean temp) {
            this.file = file;
            this.wordMs = wordMs;
            this.temp = temp;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean bound;

    // Синтезаторы телефона (Google, VoxSherpa, RHVoice…)
    private PhoneVoices phone;
    private final java.util.LinkedList<String> errorLog = new java.util.LinkedList<>();
    private MediaPlayer previewPlayer;
    private volatile long lastPhoneWarning;

    // Текущий сеанс
    private volatile int session;
    private volatile int state = STOPPED;
    private volatile boolean buffering;
    private volatile List<Segment> score = new ArrayList<>();
    private volatile Settings settings = new Settings();
    private volatile String engineInUse = Settings.ENGINE_EDGE;
    private String docKey, title = "";
    private volatile int cur;
    private int curWord;
    private final Map<Integer, Clip> clips = new ConcurrentHashMap<>();
    private final Object prodLock = new Object();
    private MediaPlayer mp, nextMp;
    private boolean pausedByFocus;
    private int taskDone, taskTotal;
    private int cacheWrites;

    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private boolean noisyRegistered;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (state == PLAYING) pause(); // наушники отключили — ставим на паузу
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            if (state == PLAYING) main.postDelayed(this, 80);
        }
    };

    // ------------------------------------------------------------ жизненный цикл

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chtec:reading");
        wakeLock.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Чтение вслух", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        mediaSession = new MediaSession(this, "Chtec");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (state == PAUSED) resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                skip(1);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-1);
            }

            @Override
            public void onStop() {
                stop();
            }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        phone = new PhoneVoices(this, main, new PhoneVoices.Callback() {
            @Override
            public void onChanged() {
                notifyVoices();
            }

            @Override
            public void log(String msg) {
                logError(msg);
            }
        });
        phone.refresh(Settings.load(this), false, false);
    }

    private void notifyVoices() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onVoicesReady();
            }
        });
    }

    /** Заново спросить у Android список синтезаторов; all — подключиться ко всем (для списка голосов). */
    void refreshPhoneVoices(boolean all, boolean reconnect) {
        phone.refresh(Settings.load(this), all, reconnect);
    }

    List<PhoneVoices.Item> getPhoneVoices() {
        return phone.voices();
    }

    List<PhoneVoices.Engine> getPhoneEngines() {
        return phone.engines();
    }

    /** Основной синтезатор в телефоне, закрытый для приложений (Samsung на One UI 7+), или null. */
    String hiddenEngineLabel() {
        return phone.hiddenDefaultLabel();
    }

    String mainEngineLabel(Settings s) {
        return phone.label(phone.mainPkg(s));
    }

    String phoneProblem(Settings s) {
        return phone.problem(s);
    }

    /** «▶ Проба»: короткая фраза выбранным голосом. */
    void preview(final Settings s, boolean microsoft) {
        previewSegment(new Segment("Здравствуйте! Так звучит этот голос. Нравится?", "narrator", "statement", 0, 0),
                s, microsoft);
    }

    /** Проба голоса персонажа: фраза с его голосом, высотой и темпом. */
    void previewRole(String edgeVoice, String phoneVoice, int pitch, int rate, Settings s, boolean microsoft) {
        Segment seg = new Segment("Вот так буду говорить я. Похоже?", "character", "statement", 0, 0);
        seg.edgeVoice = edgeVoice;
        seg.phoneVoice = phoneVoice;
        seg.pitchShift = pitch;
        seg.rateShift = rate;
        previewSegment(seg, s, microsoft);
    }

    private void previewSegment(final Segment seg, final Settings s, boolean microsoft) {
        if (state != STOPPED) {
            postMessage("Проба голоса работает, когда чтение остановлено.", false);
            return;
        }
        stopPreview();
        if (!microsoft) {
            String key = seg.phoneVoice != null ? seg.phoneVoice : s.systemNarrator;
            if (!phone.speak(key, s, seg.text, seg.pitchShift, seg.rateShift)) {
                String why = phone.problem(s);
                postMessage(why != null ? why : "Этот голос не смог прочитать пробную фразу.", true);
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Clip c = edgeClip(seg, s, true);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                stopPreview();
                                previewPlayer = newPlayer(c);
                                previewPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                    @Override
                                    public void onCompletion(MediaPlayer m) {
                                        stopPreview();
                                    }
                                });
                                previewPlayer.start();
                            } catch (IOException e) {
                                postMessage("Не удалось проиграть пробу: " + reason(e), true);
                            }
                        }
                    });
                } catch (Exception e) {
                    postMessage("Голос Microsoft недоступен: " + reason(e), true);
                }
            }
        }, "chtec-preview").start();
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        phone.stopAll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) {
            if (state == PLAYING) pause();
            else if (state == PAUSED) resume();
        } else if (ACTION_STOP.equals(action)) {
            stop();
        } else if (ACTION_NEXT.equals(action)) {
            skip(1);
        } else if (ACTION_PREV.equals(action)) {
            skip(-1);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        bound = true;
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        bound = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        bound = false;
        listener = null;
        if (state == STOPPED) stopSelf();
        return true;
    }

    @Override
    public void onDestroy() {
        stop();
        stopPreview();
        mediaSession.release();
        phone.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------ для экрана

    void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onState(state, buffering);
            l.onVoicesReady();
            if ((state == PLAYING || state == PAUSED) && cur < score.size()) l.onPosition(cur, curWord);
        }
    }

    int getState() {
        return state;
    }

    List<Segment> getScore() {
        return score;
    }

    String getDocKey() {
        return docKey;
    }

    /** Позиция текущего слова в тексте на экране (для закладки). */
    int currentTextPosition() {
        List<Segment> sc = score;
        if (cur < 0 || cur >= sc.size()) return -1;
        Segment s = sc.get(cur);
        if (curWord >= 0 && curWord < s.wordStart.length) return s.wordStart[curWord];
        return s.srcStart;
    }

    void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            postMessage("Откройте вручную: Настройки → Специальные возможности → Синтез речи.", true);
        }
    }

    long cacheSize() {
        long total = 0;
        File[] files = edgeDir().listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    void clearCache() {
        File[] files = edgeDir().listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ------------------------------------------------------------ воспроизведение (главный поток)

    void play(List<Segment> newScore, Settings s, String key, String newTitle, int startIndex) {
        stop();
        stopPreview();
        if (newScore.isEmpty()) return;
        final int my = ++session;
        score = newScore;
        settings = s;
        docKey = key;
        title = newTitle == null ? "" : newTitle;
        cur = Math.max(0, Math.min(startIndex, newScore.size() - 1));
        curWord = 0;
        engineInUse = resolveEngine(s, newScore.get(cur));
        if (Settings.ENGINE_SYSTEM.equals(engineInUse) && !phone.isReady(s)) {
            String why = phone.problem(s);
            postMessage(why != null ? why : "Голос телефона ещё загружается — попробуйте через пару секунд.", true);
            phone.refresh(s, false, false);
            return;
        }
        beginForeground(PLAYING);
        new Thread(new Runnable() {
            @Override
            public void run() {
                producerLoop(my);
            }
        }, "chtec-producer").start();
        startCurrent();
    }

    void pause() {
        if (state != PLAYING) return;
        if (mp != null) {
            try {
                mp.pause();
            } catch (IllegalStateException ignored) {
                // как раз закончился
            }
        }
        setState(PAUSED);
        saveLastPosition();
    }

    void resume() {
        if (state != PAUSED) return;
        pausedByFocus = false;
        requestFocus();
        setState(PLAYING);
        if (mp != null) {
            try {
                mp.start();
            } catch (IllegalStateException e) {
                startCurrent();
            }
        } else {
            startCurrent();
        }
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    /** На фрагмент вперёд/назад. «Назад» в начале фрагмента — к предыдущему, иначе — к началу текущего. */
    void skip(int delta) {
        if (state != PLAYING && state != PAUSED) return;
        int target = cur + delta;
        if (delta < 0 && mp != null) {
            try {
                if (mp.getCurrentPosition() > 2500) target = cur;
            } catch (IllegalStateException ignored) {
                // не страшно
            }
        }
        target = Math.max(0, Math.min(target, score.size() - 1));
        releasePlayers();
        cur = target;
        curWord = 0;
        wakeProducer();
        startCurrent();
    }

    void stop() {
        if (state == STOPPED) return;
        saveLastPosition();
        session++;
        wakeProducer();
        releasePlayers();
        main.removeCallbacks(ticker);
        for (Clip c : clips.values()) if (c.temp) //noinspection ResultOfMethodCallIgnored
            c.file.delete();
        clips.clear();
        phone.stopAll();
        endForeground();
    }

    private void startCurrent() {
        if (state != PLAYING && state != PAUSED) return;
        Clip c = clips.get(cur);
        if (c == null) {
            setBuffering(true); // ждём, пока фрагмент озвучится
            return;
        }
        setBuffering(false);
        try {
            mp = newPlayer(c);
        } catch (IOException e) {
            onClipFinished(null); // битый файл — пропускаем фрагмент
            return;
        }
        if (state == PLAYING) mp.start();
        reportPosition(0, true);
        prepareNext();
        main.removeCallbacks(ticker);
        if (state == PLAYING) main.post(ticker);
    }

    private MediaPlayer newPlayer(Clip c) throws IOException {
        final MediaPlayer m = new MediaPlayer();
        m.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        m.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        try {
            m.setDataSource(c.file.getAbsolutePath());
            m.prepare();
        } catch (IOException | RuntimeException e) {
            m.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer done) {
                onClipFinished(done);
            }
        });
        m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer bad, int what, int extra) {
                onClipFinished(bad);
                return true;
            }
        });
        return m;
    }

    /** Следующий фрагмент подключается к текущему заранее — переход без зазора. */
    private void prepareNext() {
        if (mp == null || nextMp != null || cur + 1 >= score.size()) return;
        Clip n = clips.get(cur + 1);
        if (n == null) return;
        try {
            nextMp = newPlayer(n);
            mp.setNextMediaPlayer(nextMp);
        } catch (IOException | IllegalStateException e) {
            if (nextMp != null) nextMp.release();
            nextMp = null;
        }
    }

    private void onClipFinished(MediaPlayer done) {
        if (done != null && done != mp) {
            if (done == nextMp) {
                nextMp = null;
                try {
                    if (mp != null) mp.setNextMediaPlayer(null); // следующий сломался — подготовим заново
                } catch (IllegalStateException ignored) {
                    // не страшно
                }
            }
            done.release();
            return;
        }
        if (state != PLAYING && state != PAUSED) return;
        MediaPlayer old = mp;
        mp = null;
        if (old != null) old.release();
        Clip played = clips.remove(cur);
        if (played != null && played.temp) //noinspection ResultOfMethodCallIgnored
            played.file.delete();
        cur++;
        curWord = 0;
        wakeProducer();
        if (cur >= score.size()) {
            cur = score.size() - 1;
            postMessage("Чтение окончено.", false);
            Shelf.get(this).markFinished(docKey);
            stop();
            return;
        }
        if (nextMp != null) {
            // Следующий плеер уже заиграл сам (setNextMediaPlayer).
            mp = nextMp;
            nextMp = null;
            setBuffering(false);
            reportPosition(0, true);
            prepareNext();
        } else {
            startCurrent();
        }
    }

    private void onClipReady(int my, int index) {
        if (my != session) return;
        if (index == cur && mp == null) startCurrent();
        else if (index == cur + 1) prepareNext();
    }

    private void tick() {
        if (mp == null) return;
        Clip c = clips.get(cur);
        if (c == null || c.wordMs.length == 0) return;
        int pos;
        try {
            pos = mp.getCurrentPosition();
        } catch (IllegalStateException e) {
            return;
        }
        int w = Timing.wordAt(c.wordMs, pos);
        if (w != curWord) reportPosition(w, false);
    }

    private void reportPosition(int word, boolean newSegment) {
        curWord = word;
        if (cur < 0 || cur >= score.size()) return;
        if (newSegment) {
            Segment s = score.get(cur);
            if (docKey != null && s.srcStart >= 0) Library.get(this).setLastPosition(docKey, s.srcStart, title);
            updateMediaInfo();
        }
        if (listener != null) listener.onPosition(cur, word);
    }

    private void saveLastPosition() {
        int pos = currentTextPosition();
        if (docKey != null && pos >= 0) Library.get(this).setLastPosition(docKey, pos, title);
    }

    private void releasePlayers() {
        if (nextMp != null) nextMp.release();
        nextMp = null;
        if (mp != null) mp.release();
        mp = null;
    }

    // ------------------------------------------------------------ подготовка звука (фоновый поток)

    private void producerLoop(int my) {
        ExecutorService pool = Executors.newFixedThreadPool(EDGE_PARALLEL);
        Map<Integer, Future<Clip>> pending = new HashMap<>();
        int edgeFailures = 0; // подряд неудачных фраз Microsoft
        try {
            while (my == session) {
                List<Segment> sc = score;
                int base = Math.max(0, cur);
                boolean edgeNow = Settings.ENGINE_EDGE.equals(engineInUse);
                int end = Math.min(sc.size(), base + (edgeNow ? AHEAD_EDGE : AHEAD_PHONE));
                for (Integer k : new ArrayList<>(clips.keySet())) {
                    if (k < base - 1 || k >= end + 2) {
                        Clip c = clips.remove(k);
                        if (c != null && c.temp) //noinspection ResultOfMethodCallIgnored
                            c.file.delete();
                    }
                }
                boolean worked = false;
                for (Iterator<Map.Entry<Integer, Future<Clip>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Integer, Future<Clip>> e = it.next();
                    if (!e.getValue().isDone()) continue;
                    it.remove();
                    worked = true;
                    try {
                        publish(my, e.getKey(), e.getValue().get());
                        edgeFailures = 0;
                    } catch (CancellationException ignored) {
                        // отменён
                    } catch (ExecutionException ex) {
                        logError("Microsoft, фраза " + (e.getKey() + 1) + ": " + reason(ex));
                        edgeFailures++;
                        if (edgeFailures >= 3) {
                            // Три фразы подряд не загрузились — дальше голосом телефона.
                            if (!fallbackToSystem(ex)) {
                                postMessage("Не удалось озвучить: " + reason(ex), true);
                                stopFromWorker(my);
                                return;
                            }
                            for (Future<Clip> f : pending.values()) f.cancel(true);
                            pending.clear();
                            break;
                        }
                        // Одна фраза не загрузилась — читаем её голосом телефона, Microsoft пробуем дальше.
                        int k = e.getKey();
                        if (phone.isReady(settings) && k < sc.size()) {
                            publish(my, k, systemClip(sc.get(k), settings, new File(sysDir(), "c" + my + "_" + k + ".wav")));
                        }
                    }
                }
                for (int i = base; i < end && my == session; i++) {
                    if (clips.containsKey(i) || pending.containsKey(i)) continue;
                    if (Settings.ENGINE_EDGE.equals(engineInUse)) {
                        if (pending.size() < EDGE_PARALLEL) {
                            final Segment seg = sc.get(i);
                            final Settings s = settings;
                            pending.put(i, pool.submit(new Callable<Clip>() {
                                @Override
                                public Clip call() throws Exception {
                                    return edgeClip(seg, s, true);
                                }
                            }));
                            worked = true;
                        }
                    } else {
                        publish(my, i, systemClip(sc.get(i), settings, new File(sysDir(), "c" + my + "_" + i + ".wav")));
                        worked = true;
                        break; // окно могло сдвинуться — пересчитаем
                    }
                }
                if (!worked) {
                    synchronized (prodLock) {
                        prodLock.wait(150);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // остановлено
        } catch (Exception e) {
            if (my == session) {
                postMessage("Ошибка озвучки: " + reason(e), true);
                stopFromWorker(my);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void publish(final int my, final int index, Clip c) {
        if (my != session) {
            if (c.temp) //noinspection ResultOfMethodCallIgnored
                c.file.delete();
            return;
        }
        clips.put(index, c);
        main.post(new Runnable() {
            @Override
            public void run() {
                onClipReady(my, index);
            }
        });
    }

    private boolean fallbackToSystem(Exception e) {
        if (!phone.isReady(settings)) return false;
        logError("Microsoft: переключаюсь на голос телефона");
        engineInUse = Settings.ENGINE_SYSTEM;
        postMessage("Голоса Microsoft недоступны (" + reason(e) + "). Дальше читаю голосом телефона — он работает без интернета.", false);
        return true;
    }

    private void wakeProducer() {
        synchronized (prodLock) {
            prodLock.notifyAll();
        }
    }

    private void stopFromWorker(final int my) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (my == session) stop();
            }
        });
    }

    // ------------------------------------------------------------ озвучка фрагментов

    private File edgeDir() {
        File d = new File(getFilesDir(), "voice-cache");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File sysDir() {
        File d = new File(getCacheDir(), "phone-voice");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Голос и интонация фрагмента для Microsoft: {голос, темп, высота, громкость}. */
    private static Object[] edgeParams(Segment seg, Settings s) {
        int[] p = Prepare.PROSODY.get(seg.style);
        int dRate = p[0], dPitch = p[1];
        String voice = s.edgeNarrator;
        if (seg.edgeVoice != null) {
            // У персонажа свой голос (роль): голос, высота и темп — на всю книгу.
            voice = seg.edgeVoice;
            dRate += seg.rateShift;
            dPitch += seg.pitchShift;
        } else if (seg.role.equals("character")) {
            if (!s.edgeCharacter.isEmpty()) {
                voice = s.edgeCharacter;
            } else {
                dRate += 3; // тот же актёр, но реплики персонажей чуть живее и выше
                dPitch += 3;
            }
        }
        return new Object[]{voice, s.rate + dRate, s.pitch + dPitch, p[2]};
    }

    private File edgeCacheFile(Segment seg, Settings s, String ext) {
        Object[] p = edgeParams(seg, s);
        String id = p[0] + "|" + p[1] + "|" + p[2] + "|" + p[3] + "|" + seg.pauseAfter + "|" + seg.text;
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return new File(edgeDir(), sb + ext);
        } catch (Exception e) {
            return new File(edgeDir(), Integer.toHexString(id.hashCode()) + ext);
        }
    }

    private boolean edgeCached(Segment seg, Settings s) {
        return edgeCacheFile(seg, s, ".mp3").exists() && edgeCacheFile(seg, s, ".txt").exists();
    }

    /** Фрагмент голосом Microsoft: из сохранённого на телефоне или из интернета (и сразу сохраняем). */
    private Clip edgeClip(Segment seg, Settings s, boolean allowNetwork) throws Exception {
        File mp3 = edgeCacheFile(seg, s, ".mp3");
        File meta = edgeCacheFile(seg, s, ".txt");
        if (mp3.exists() && meta.exists()) {
            long now = System.currentTimeMillis();
            //noinspection ResultOfMethodCallIgnored
            mp3.setLastModified(now);
            return new Clip(mp3, parseInts(readText(meta)), false);
        }
        if (!allowNetwork || !online()) throw new IOException("нет интернета");
        Object[] p = edgeParams(seg, s);
        EdgeTts.Speech sp = null;
        IOException last = null;
        for (int attempt = 0; attempt < 4 && sp == null; attempt++) {
            try {
                sp = EdgeTts.synthesize(seg.text, (String) p[0], (Integer) p[1], (Integer) p[2], (Integer) p[3]);
            } catch (IOException e) {
                last = e;
                if (!online()) break;
                Thread.sleep(1000L << attempt);
            }
        }
        if (sp == null) throw last;
        int[] wordMs = Timing.fromBoundaries(seg.text, sp.offsetsMs, sp.words, sp.durationMs());
        File tmp = new File(mp3.getPath() + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(sp.audio);
            out.write(EdgeTts.silence(Math.max(seg.pauseAfter, 24)));
        } finally {
            out.close();
        }
        writeText(meta, joinInts(wordMs));
        if (!tmp.renameTo(mp3)) throw new IOException("не удалось сохранить звук");
        if (++cacheWrites % 25 == 0) trimCache();
        return new Clip(mp3, wordMs, false);
    }

    /** Фрагмент голосом телефона (без интернета): WAV вместе с паузой после него. */
    /** Фрагмент голосом телефона (без интернета): WAV вместе с паузой после него. */
    private Clip systemClip(Segment seg, Settings s, File out) throws Exception {
        File synth = new File(sysDir(), "synth_" + Thread.currentThread().getId() + ".wav");
        boolean ok = phone.synth(seg, s, synth, new PhoneVoices.Online() {
            @Override
            public boolean now() {
                return online();
            }
        });
        if (!ok) {
            logError("Голос телефона не прочитал фразу: " + clip(seg.text));
            if (System.currentTimeMillis() - lastPhoneWarning > 60000) { // не чаще раза в минуту
                lastPhoneWarning = System.currentTimeMillis();
                postMessage("Голос телефона не смог прочитать фразу. Нажмите «Проверить голоса и связь» в настройках — "
                        + "отчёт покажет, что не так.", false);
            }
        }
        int rate = 22050;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long speech = 0;
        if (ok) {
            rate = Wav.info(synth).rate;
            speech = Wav.appendPcm(synth, rate, pcm);
        }
        //noinspection ResultOfMethodCallIgnored
        synth.delete();
        Wav.appendSilence(Math.max(seg.pauseAfter, 30), rate, pcm);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            Wav.writeHeader(fo, rate, pcm.size());
            pcm.writeTo(fo);
        } finally {
            fo.close();
        }
        int speechMs = (int) (speech * 1000 / (rate * 2L));
        return new Clip(out, Timing.estimate(seg.text, speechMs), true);
    }

    private String resolveEngine(Settings s, Segment first) {
        if (Settings.ENGINE_SYSTEM.equals(s.engine)) return Settings.ENGINE_SYSTEM;
        if (Settings.ENGINE_EDGE.equals(s.engine)) return Settings.ENGINE_EDGE;
        // Авто: Microsoft, если есть интернет или книга уже подготовлена; иначе — голос телефона.
        if (online() || edgeCached(first, s) || !phone.isReady(s)) return Settings.ENGINE_EDGE;
        return Settings.ENGINE_SYSTEM;
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void trimCache() {
        File[] files = edgeDir().listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= CACHE_LIMIT) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= CACHE_LIMIT * 8 / 10) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ------------------------------------------------------------ сохранение в файл и подготовка для офлайна

    /** Каким голосом будет озвучено (для режима «Авто» зависит от интернета). */
    String engineFor(List<Segment> sc, Settings s) {
        return sc.isEmpty() ? Settings.ENGINE_EDGE : resolveEngine(s, sc.get(0));
    }

    void save(final List<Segment> sc, final Settings s, final Uri uri, String newTitle, final String engine) {
        stop();
        if (sc.isEmpty()) return;
        final int my = ++session;
        title = newTitle == null ? "" : newTitle;
        beginForeground(SAVING);
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveTask(my, sc, s, engine, uri);
            }
        }, "chtec-save").start();
    }

    void prepareOffline(final List<Segment> sc, final Settings s, String newTitle) {
        stop();
        if (sc.isEmpty()) return;
        final int my = ++session;
        title = newTitle == null ? "" : newTitle;
        beginForeground(PREPARING);
        new Thread(new Runnable() {
            @Override
            public void run() {
                prepareTask(my, sc, s);
            }
        }, "chtec-prepare").start();
    }

    private void prepareTask(int my, final List<Segment> sc, final Settings s) {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        boolean ok = false;
        try {
            if (!online()) throw new IOException("нет интернета");
            List<Future<Clip>> futures = new ArrayList<>();
            for (final Segment seg : sc) {
                futures.add(pool.submit(new Callable<Clip>() {
                    @Override
                    public Clip call() throws Exception {
                        return edgeClip(seg, s, true);
                    }
                }));
            }
            for (int i = 0; i < futures.size() && my == session; i++) {
                futures.get(i).get();
                postTaskProgress(i + 1, sc.size());
            }
            ok = my == session;
        } catch (InterruptedException ignored) {
            // остановлено
        } catch (Exception e) {
            if (my == session) postMessage("Подготовка прервана: " + reason(e) + ". Уже готовое сохранено.", true);
        } finally {
            pool.shutdownNow();
        }
        if (ok) postMessage("Книга подготовлена — теперь её можно слушать голосом Microsoft без интернета.", false);
        stopFromWorker(my);
    }

    private void saveTask(int my, List<Segment> sc, Settings s, String engine, Uri uri) {
        boolean ok = false;
        OutputStream os = null;
        File raw = new File(sysDir(), "save.pcm");
        File part = new File(sysDir(), "save_part.wav");
        try {
            os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("не удалось создать файл");
            if (Settings.ENGINE_EDGE.equals(engine)) {
                for (int i = 0; i < sc.size() && my == session; i++) {
                    Clip c = edgeClip(sc.get(i), s, true);
                    copy(c.file, os);
                    postTaskProgress(i + 1, sc.size());
                }
            } else {
                if (!phone.isReady(s)) {
                    String why = phone.problem(s);
                    throw new IOException(why != null ? why : "голос телефона не готов");
                }
                int rate = 0;
                long length = 0;
                OutputStream pcm = new FileOutputStream(raw);
                try {
                    for (int i = 0; i < sc.size() && my == session; i++) {
                        Clip c = systemClip(sc.get(i), s, part);
                        if (rate == 0) rate = Wav.info(c.file).rate;
                        length += Wav.appendPcm(c.file, rate, pcm);
                        postTaskProgress(i + 1, sc.size());
                    }
                } finally {
                    pcm.close();
                }
                if (my == session) {
                    Wav.writeHeader(os, rate, length);
                    copy(raw, os);
                }
            }
            ok = my == session;
        } catch (InterruptedException ignored) {
            // остановлено
        } catch (Exception e) {
            if (my == session) postMessage("Не удалось сохранить: " + reason(e), true);
        } finally {
            try {
                if (os != null) os.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        if (ok) {
            postMessage("Готово, файл сохранён.", false);
        } else {
            try {
                DocumentsContract.deleteDocument(getContentResolver(), uri); // не оставляем недописанный файл
            } catch (Exception ignored) {
                // не страшно
            }
        }
        stopFromWorker(my);
    }

    // ------------------------------------------------------------ звук, уведомление, состояние

    private void beginForeground(int newState) {
        // Служба должна быть «запущенной», а не только подключённой, — иначе её нельзя вывести на передний план.
        startService(new Intent(this, ReaderService.class));
        wakeLock.acquire(12 * 60 * 60 * 1000L);
        taskDone = 0;
        taskTotal = 0;
        if (newState == PLAYING) {
            requestFocus();
            mediaSession.setActive(true);
            if (!noisyRegistered) {
                registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                noisyRegistered = true;
            }
        }
        state = newState;
        buffering = false;
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFY_ID, n);
        }
        setState(newState);
    }

    @SuppressWarnings("deprecation")
    private void endForeground() {
        if (noisyRegistered) {
            unregisterReceiver(noisy);
            noisyRegistered = false;
        }
        ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocus(this);
        mediaSession.setActive(false);
        if (wakeLock.isHeld()) wakeLock.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        setState(STOPPED);
        if (!bound) stopSelf();
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        ((AudioManager) getSystemService(AUDIO_SERVICE))
                .requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (state == PLAYING) {
                pausedByFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT; // например, звонок
                pause();
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus && state == PAUSED) {
            resume();
        }
    }

    private void setState(int s) {
        state = s;
        updateMediaInfo();
        updateNotification();
        if (listener != null) listener.onState(s, buffering);
    }

    private void setBuffering(boolean b) {
        if (buffering == b) return;
        buffering = b;
        if (listener != null) listener.onState(state, b);
    }

    private String currentSentence() {
        List<Segment> sc = score;
        if (state == SAVING) return "Сохраняю в файл: " + taskDone + " из " + taskTotal;
        if (state == PREPARING) return "Готовлю для офлайна: " + taskDone + " из " + taskTotal;
        if (cur < 0 || cur >= sc.size()) return "";
        String t = sc.get(cur).text;
        return t.length() > 140 ? t.substring(0, 140) + "…" : t;
    }

    private void updateMediaInfo() {
        int ps = state == PLAYING ? PlaybackState.STATE_PLAYING
                : state == PAUSED ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? "Чтец" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSentence())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, currentSentence())
                .build());
        updateNotification();
    }

    private Notification.Action action(int icon, String label, String act, int code) {
        Intent i = new Intent(this, ReaderService.class).setAction(act);
        PendingIntent pi = PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.isEmpty() ? "Чтец" : title)
                .setContentText(currentSentence())
                .setOngoing(state != PAUSED)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (state == PLAYING || state == PAUSED) {
            b.addAction(action(R.drawable.ic_prev, "Назад", ACTION_PREV, 3));
            b.addAction(state == PLAYING ? action(R.drawable.ic_pause, "Пауза", ACTION_TOGGLE, 1)
                    : action(R.drawable.ic_play, "Продолжить", ACTION_TOGGLE, 1));
            b.addAction(action(R.drawable.ic_next, "Вперёд", ACTION_NEXT, 4));
            b.addAction(action(R.drawable.ic_stop, "Стоп", ACTION_STOP, 2));
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        } else {
            if (taskTotal > 0) b.setProgress(taskTotal, taskDone, false);
            b.addAction(action(R.drawable.ic_stop, "Отмена", ACTION_STOP, 2));
        }
        return b.build();
    }

    private void updateNotification() {
        if (state == STOPPED) return;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, buildNotification());
    }

    private void postTaskProgress(final int done, final int total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                taskDone = done;
                taskTotal = total;
                updateNotification();
                if (listener != null) listener.onTaskProgress(done, total);
            }
        });
    }

    private void postMessage(final String text, final boolean error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onMessage(text, error);
            }
        });
    }

    // ------------------------------------------------------------ мелочи

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String reason(Throwable e) {
        Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (c instanceof java.net.UnknownHostException) return "нет интернета";
        if (c instanceof java.net.SocketTimeoutException) return "сервер не отвечает";
        String m = c.getMessage();
        return m != null && !m.isEmpty() ? m : c.getClass().getSimpleName();
    }

    private static void copy(File f, OutputStream os) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        } finally {
            in.close();
        }
    }

    private static String readText(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(f, out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static String joinInts(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static int[] parseInts(String s) {
        s = s.trim();
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Integer.parseInt(parts[i].trim());
        return r;
    }

    // ------------------------------------------------------------ журнал ошибок и самопроверка

    private static String clip(String t) {
        return t.length() > 50 ? t.substring(0, 50) + "…" : t;
    }

    private void logError(String msg) {
        String line = android.text.format.DateFormat.format("dd.MM HH:mm:ss", System.currentTimeMillis()) + " — " + msg;
        synchronized (errorLog) {
            errorLog.add(line);
            while (errorLog.size() > 40) errorLog.removeFirst();
        }
    }

    interface DiagnosticsListener {
        void onLine(String line);

        void onDone(String report);
    }

    /**
     * Самопроверка на этом телефоне: какие синтезаторы видны, читает ли каждый русский голос,
     * отвечают ли голоса Microsoft. Отчёт можно скопировать и прислать.
     */
    void runDiagnostics(final DiagnosticsListener out) {
        stop();
        stopPreview();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder r = new StringBuilder();
                Line line = new Line() {
                    @Override
                    public void add(final String s) {
                        r.append(s).append('\n');
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                out.onLine(s);
                            }
                        });
                    }
                };
                try {
                    diagnose(line);
                } catch (Throwable t) {
                    line.add("ОШИБКА самопроверки: " + t);
                }
                final String report = r.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        out.onDone(report);
                    }
                });
            }
        }, "chtec-diagnostics").start();
    }

    private interface Line {
        void add(String s);
    }

    private void diagnose(Line line) throws InterruptedException {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // неважно
        }
        line.add("ОТЧЁТ ЧТЕЦА " + version + ", " + android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", System.currentTimeMillis()));
        line.add("Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")" + oneUi());
        line.add("Интернет: " + (online() ? "есть" : "нет"));
        line.add("");
        line.add("— Синтезаторы речи —");
        Settings st = Settings.load(this);
        String def = phone.phoneDefault();
        line.add("Основной в настройках телефона: " + phone.label(def) + " (" + def + ")");
        Map<String, String> inst = phone.installed();
        if (inst.isEmpty()) line.add("Видимых приложению синтезаторов: НЕТ");
        for (Map.Entry<String, String> e : inst.entrySet()) line.add("  установлен: " + e.getValue() + " (" + e.getKey() + ")");
        if (phone.hiddenDefaultLabel() != null) {
            line.add("  НЕ виден: " + phone.hiddenDefaultLabel() + " — производитель закрыл его для сторонних приложений");
        }
        // Подключаемся ко всем и ждём до 15 секунд, пока ответят.
        phone.refresh(st, true, true);
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            boolean waiting = false;
            for (PhoneVoices.Engine e : phone.engines()) if (e.status == 0) waiting = true;
            if (!waiting) break;
            Thread.sleep(300);
        }
        line.add("Стандартный голос берётся из: " + phone.label(phone.mainPkg(st)));
        String why = phone.problem(st);
        if (why != null) line.add("Проблема: " + why);
        File f = new File(sysDir(), "diag.wav");
        int tested = 0;
        for (PhoneVoices.Engine e : phone.engines()) {
            line.add("");
            String state = e.status == 1 ? "готов" : e.status == 0 ? "НЕ ответил за 15 с" : "НЕ подключился";
            line.add("[" + e.label + "] " + state + ", голосов: " + e.voices.size() + ", русских: " + e.russianVoices()
                    + (e.russianLanguage ? ", русский язык поддерживает" : ", русский язык НЕ поддерживает"));
            if (e.status != 1) continue;
            line.add(testVoice(e, null, "стандартный русский", f));
            int n = 0;
            for (Voice v : new ArrayList<>(e.voices.values())) {
                if (!PhoneVoices.isRussian(v)) continue;
                if (++n > 12 || ++tested > 30) {
                    line.add("  …остальные голоса пропущены");
                    break;
                }
                if (v.isNetworkConnectionRequired() && !online()) {
                    line.add("  " + v.getName() + ": пропущен (нужен интернет)");
                    continue;
                }
                line.add(testVoice(e, v, v.getName() + (v.isNetworkConnectionRequired() ? " [сетевой]" : ""), f));
            }
            int other = e.voices.size() - e.russianVoices();
            if (other > 0) line.add("  + не русских голосов: " + other + " (русский текст читать не будут)");
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        line.add("");
        line.add("— Голоса Microsoft —");
        if (!online()) {
            line.add("Пропущено: нет интернета");
        } else {
            try {
                long t0 = System.currentTimeMillis();
                java.net.InetAddress.getByName("speech.platform.bing.com");
                line.add("Сервер найден за " + (System.currentTimeMillis() - t0) + " мс");
            } catch (Exception e) {
                line.add("Сервер НЕ найден: " + reason(e));
            }
            int ok = 0;
            for (int i = 1; i <= 3; i++) {
                long t0 = System.currentTimeMillis();
                try {
                    EdgeTts.Speech sp = EdgeTts.synthesize("Проверка связи номер " + i + ".", EdgeTts.DMITRY, 0, 0, 0);
                    ok++;
                    line.add("  попытка " + i + ": OK за " + (System.currentTimeMillis() - t0) + " мс, звук "
                            + sp.durationMs() + " мс, меток слов " + sp.words.length);
                } catch (Exception e) {
                    line.add("  попытка " + i + ": ОШИБКА за " + (System.currentTimeMillis() - t0) + " мс — "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            line.add("Итог: " + ok + " из 3");
        }
        line.add("");
        line.add("Сохранённая озвучка Microsoft: " + cacheSize() / (1024 * 1024) + " МБ");
        synchronized (errorLog) {
            line.add("— Последние ошибки (" + errorLog.size() + ") —");
            for (String e : errorLog) line.add("  " + e);
        }
    }

    private String testVoice(PhoneVoices.Engine e, Voice v, String label, File f) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        boolean ok = phone.synthWith(e, v, "Проверка голоса. Раз, два, три.", 1f, 1f, 1f, f, 30);
        long ms = System.currentTimeMillis() - t0;
        if (!ok) return "  " + label + ": НЕ РАБОТАЕТ (" + ms + " мс)";
        try {
            Wav.Info info = Wav.info(f);
            return "  " + label + ": OK, озвучено за " + ms + " мс, " + info.rate + " Гц";
        } catch (IOException ex) {
            return "  " + label + ": звук повреждён — " + ex.getMessage();
        }
    }

    /** Версия One UI на телефонах Samsung (поле SEM_PLATFORM_INT есть только там). */
    private static String oneUi() {
        try {
            int v = Build.VERSION.class.getField("SEM_PLATFORM_INT").getInt(null);
            int ui = v - 90000;
            return ", One UI " + ui / 10000 + "." + (ui % 10000) / 100;
        } catch (Exception e) {
            return "";
        }
    }

    String errorLogText() {
        synchronized (errorLog) {
            StringBuilder sb = new StringBuilder();
            for (String e : errorLog) sb.append(e).append('\n');
            return sb.toString();
        }
    }
}
