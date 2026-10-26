package ru.chtec;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.chtec.core.Aligner;
import ru.chtec.core.Block;
import ru.chtec.core.EdgeTts;
import ru.chtec.core.Prepare;
import ru.chtec.core.Segment;
import ru.chtec.core.TextExtractor;

public class MainActivity extends Activity implements ReaderService.Listener {
    static final String EXTRA_BOOK = "book";   // открыть книгу из библиотеки
    static final String EXTRA_PASTE = "paste"; // вставить свой текст
    private static final int REQ_SAVE = 2;
    private static final long MAX_FILE = 60L * 1024 * 1024;
    private static final long FOLLOW_PAUSE_AFTER_TOUCH = 4000;
    private static final int READ_WINDOW = 2000;

    private static final String[] EDGE_NAMES = {"Дмитрий (мужской)", "Светлана (женский)"};
    private static final String[] EDGE_IDS = {EdgeTts.DMITRY, EdgeTts.SVETLANA};

    // Цвета подсветки
    private static final int COLOR_READ = 0xFF9A9088;      // прочитанное
    private static final int COLOR_SENTENCE = 0xFFFDEFC8;  // текущая фраза
    private static final int COLOR_WORD = 0xFFF6BE4F;      // текущее слово
    private static final int COLOR_BOOKMARK = 0xFFE4D9F5;  // закладки

    private ReaderService service;
    private Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText text;
    private TextView fileName, status, now;
    private ImageButton play, edit;
    private ProgressBar progress;

    private String title = "";
    private String currentBookId; // книга из библиотеки, которая сейчас открыта
    private String docKey;
    private boolean editing;
    private long userTouchAt;
    private ObjectAnimator scroller;
    private int shownSegment = -1;
    private List<Segment> pendingSave;
    private String pendingSaveEngine;
    private boolean openedTtsSettings;

    private final ForegroundColorSpan readSpan = new ForegroundColorSpan(COLOR_READ);
    private final BackgroundColorSpan sentenceSpan = new BackgroundColorSpan(COLOR_SENTENCE);
    private final BackgroundColorSpan wordSpan = new BackgroundColorSpan(COLOR_WORD);
    private final List<Object> bookmarkSpans = new ArrayList<>();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReaderService.LocalBinder) binder).service();
            service.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    // ------------------------------------------------------------ экран

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        settings = Settings.load(this);

        text = (EditText) findViewById(R.id.text);
        fileName = (TextView) findViewById(R.id.fileName);
        status = (TextView) findViewById(R.id.status);
        now = (TextView) findViewById(R.id.now);
        play = (ImageButton) findViewById(R.id.play);
        edit = (ImageButton) findViewById(R.id.edit);
        progress = (ProgressBar) findViewById(R.id.progress);

        setupControls();
        // Текст и место чтения приложение хранит само. Если отдать их Android в «сохранённое состояние»,
        // большая книга превышает его лимит и приложение падает при сворачивании (например, при выборе файла).
        text.setSaveEnabled(false);
        text.setTextSize(settings.fontSize);
        setEditing(false);

        Intent svc = new Intent(this, ReaderService.class);
        startService(svc);
        bindService(svc, connection, BIND_AUTO_CREATE);

        if (!handleIntent(getIntent())) restoreText();
        ChtecApp.showLastCrash(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null) {
            // Могли установить новый синтезатор или скачать голоса — спрашиваем Android заново.
            service.refreshPhoneVoices(false, openedTtsSettings);
            openedTtsSettings = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        settings.save(this);
        persistText();
    }

    @Override
    protected void onDestroy() {
        if (service != null) service.setListener(null);
        unbindService(connection);
        super.onDestroy();
    }

    private void setupControls() {
        click(R.id.library, new Runnable() {
            @Override
            public void run() {
                goToLibrary();
            }
        });
        click(R.id.edit, new Runnable() {
            @Override
            public void run() {
                setEditing(!editing);
            }
        });
        click(R.id.roles, new Runnable() {
            @Override
            public void run() {
                showRoles();
            }
        });
        click(R.id.bookmarks, new Runnable() {
            @Override
            public void run() {
                showBookmarks();
            }
        });
        click(R.id.settings, new Runnable() {
            @Override
            public void run() {
                showSettings();
            }
        });
        click(R.id.bookmarkAdd, new Runnable() {
            @Override
            public void run() {
                addBookmark();
            }
        });
        click(R.id.play, new Runnable() {
            @Override
            public void run() {
                onPlayPressed();
            }
        });
        click(R.id.prev, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.skip(-1);
            }
        });
        click(R.id.next, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.skip(1);
            }
        });
        click(R.id.stop, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.stop();
            }
        });
        click(R.id.more, new Runnable() {
            @Override
            public void run() {
                showMenu();
            }
        });
        // Пока человек сам листает текст, не дёргаем прокрутку.
        text.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                userTouchAt = System.currentTimeMillis();
                if (scroller != null) scroller.cancel();
                return false;
            }
        });
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    /** Режим правки: можно печатать и вставлять. Вне его текст только читается — так он не съезжает под подсветкой. */
    private void setEditing(boolean on) {
        editing = on;
        if (on) {
            if (service != null) service.stop();
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setSingleLine(false);
            text.requestFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(text, 0);
            edit.setColorFilter(0xFFFFD27A);
            status.setText("Режим правки: можно менять и вставлять текст");
        } else {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(text.getWindowToken(), 0);
            text.setKeyListener(null);
            text.setTextIsSelectable(true);
            edit.clearColorFilter();
            String newKey = Library.docKey(text.getText().toString());
            if (!newKey.equals(docKey)) {
                docKey = newKey;
                refreshBookmarkMarks();
            }
            if (status.getText().toString().startsWith("Режим правки")) status.setText("");
        }
    }

    // ------------------------------------------------------------ чтение

    private void onPlayPressed() {
        if (service == null) return;
        int st = service.getState();
        if (st == ReaderService.PLAYING) {
            service.pause();
        } else if (st == ReaderService.PAUSED) {
            service.resume();
        } else if (st == ReaderService.STOPPED) {
            startReading();
        } else {
            toast("Сейчас идёт сохранение — дождитесь или нажмите ■");
        }
    }

    /** Строит партитуру: выделенный фрагмент или от курсора до конца. Возвращает {партитура, номер первого фрагмента}. */
    private Object[] buildScore(boolean wholeIfNoSelection) {
        String all = text.getText().toString();
        int s1 = text.getSelectionStart(), s2 = text.getSelectionEnd();
        int a = Math.max(0, Math.min(s1, s2)), b = Math.max(s1, s2);
        int base, end, cursor;
        if (b > a && b <= all.length()) {
            base = a;
            end = b;
            cursor = a;
        } else if (wholeIfNoSelection) {
            base = 0;
            end = all.length();
            cursor = 0;
        } else {
            cursor = Math.min(a, all.length());
            base = cursor == 0 ? 0 : all.lastIndexOf('\n', cursor - 1) + 1;
            end = all.length();
            if (all.substring(base).trim().isEmpty()) {
                base = 0;
                cursor = 0;
            }
        }
        String src = all.substring(base, end);
        List<Block> blocks = TextExtractor.plainToBlocks(src);
        List<Segment> score = Prepare.buildScore(blocks, settings.expressive);
        Aligner.align(score, blocks, src, base);
        // Где начинается каждый абзац в тексте на экране — по этому находим, кто говорит в абзаце.
        int[] blockStarts = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) blockStarts[i] = blocks.get(i).start < 0 ? -1 : base + blocks.get(i).start;
        int start = 0;
        for (int i = 0; i < score.size(); i++) {
            if (score.get(i).srcEnd > cursor) {
                start = i;
                break;
            }
        }
        return new Object[]{score, start, blockStarts};
    }

    // ------------------------------------------------------------ роли

    private String rolesFor;                       // для какого текста посчитаны роли
    private Map<Integer, String> speakerAtBlock = new java.util.HashMap<>();
    private Map<String, Library.Role> cast = new java.util.LinkedHashMap<>();

    /** Находит персонажей во всём тексте (в фоне) и раздаёт новым голоса; потом выполняет then. */
    private void ensureRoles(final boolean reassign, final Runnable then) {
        final String all = text.getText().toString();
        final String key = docKey != null ? docKey : Library.docKey(all);
        if (!reassign && key.equals(rolesFor)) {
            then.run();
            return;
        }
        now.setText("Определяю персонажей…");
        final List<String> phoneRu = new ArrayList<>();
        if (service != null) {
            for (PhoneVoices.Item it : service.getPhoneVoices()) if (it.russian) phoneRu.add(it.key);
        }
        final Settings s = settings.copy();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Block> blocks = TextExtractor.plainToBlocks(all);
                List<Segment> segs = Prepare.buildScore(blocks, true);
                final Map<String, ru.chtec.core.Roles.Person> found = ru.chtec.core.Roles.attribute(segs);
                final Map<Integer, String> at = new java.util.HashMap<>();
                for (Segment seg : segs) {
                    if (seg.speaker != null && blocks.get(seg.block).start >= 0) at.put(blocks.get(seg.block).start, seg.speaker);
                }
                final Map<String, Library.Role> merged = RoleVoices.merge(found, Library.get(MainActivity.this).cast(key),
                        phoneRu, s, reassign);
                Library.get(MainActivity.this).saveCast(key, merged.values());
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        speakerAtBlock = at;
                        cast = merged;
                        rolesFor = key;
                        now.setText(found.isEmpty() ? "Персонажей с репликами не найдено" : "Персонажей: " + found.size());
                        then.run();
                    }
                });
            }
        }, "chtec-roles").start();
    }

    private static String genderMark(char g) {
        return g == 'm' ? "♂" : g == 'f' ? "♀" : "?";
    }

    private boolean microsoftMode() {
        return !Settings.ENGINE_SYSTEM.equals(settings.engine);
    }

    /** Окно «Роли»: персонажи книги и их голоса. */
    private void showRoles() {
        if (text.length() == 0) {
            toast("Сначала откройте книгу");
            return;
        }
        if (docKey == null) docKey = Library.docKey(text.getText().toString());
        ensureRoles(false, new Runnable() {
            @Override
            public void run() {
                showRolesDialog();
            }
        });
    }

    private void showRolesDialog() {
        final List<Library.Role> roles = new ArrayList<>(cast.values());
        final List<PhoneVoices.Item> phone = service != null ? service.getPhoneVoices() : new ArrayList<PhoneVoices.Item>();
        int dp = (int) getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 8 * dp, 20 * dp, 0);
        final CheckBox on = new CheckBox(this);
        on.setText("Озвучивать персонажей разными голосами");
        on.setChecked(settings.roles);
        box.addView(on);
        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setTextColor(getResources().getColor(R.color.ink_soft));
        hint.setText(roles.isEmpty()
                ? "В тексте не нашлось персонажей с репликами. Роли находятся по диалогам: «— Привет, — сказал Иван»."
                : "Найдено по словам автора («сказал Иван», «ответила она») и по очерёдности реплик. "
                + "Нажмите на персонажа, чтобы выбрать ему голос. " + (microsoftMode() ? "Сейчас — голоса Microsoft."
                : "Сейчас — голоса телефона."));
        box.addView(hint);

        android.widget.ListView list = new android.widget.ListView(this);
        final List<Map<String, String>> rows = new ArrayList<>();
        for (Library.Role r : roles) {
            Map<String, String> row = new java.util.HashMap<>();
            row.put("t", r.name + "  " + genderMark(r.gender) + "  · реплик: " + r.lines);
            row.put("s", RoleVoices.describe(r, microsoftMode(), phone));
            rows.add(row);
        }
        list.setAdapter(new android.widget.SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
                new String[]{"t", "s"}, new int[]{android.R.id.text1, android.R.id.text2}));
        int h = Math.min(roles.size(), 6) * 64 * dp;
        list.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(h, 1)));
        box.addView(list);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Роли")
                .setView(box)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Раздать голоса заново", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ensureRoles(true, new Runnable() {
                            @Override
                            public void run() {
                                showRolesDialog();
                            }
                        });
                    }
                })
                .create();
        on.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                settings.roles = c;
                settings.save(MainActivity.this);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                dlg.dismiss();
                editRole(roles.get(pos));
            }
        });
        dlg.show();
    }

    private void editRole(final Library.Role r) {
        final List<PhoneVoices.Item> phone = service != null ? service.getPhoneVoices() : new ArrayList<PhoneVoices.Item>();
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 8 * dp, 20 * dp, 0);

        final Spinner gender = new Spinner(this);
        gender.setAdapter(adapter(new String[]{"Мужчина", "Женщина", "Не знаю"}));
        gender.setSelection(r.gender == 'm' ? 0 : r.gender == 'f' ? 1 : 2);
        box.addView(label("Кто это"));
        box.addView(gender);

        final Spinner edge = new Spinner(this);
        edge.setAdapter(adapter(EDGE_NAMES));
        edge.setSelection(EdgeTts.SVETLANA.equals(r.edgeVoice) ? 1 : 0);
        box.addView(label("Голос Microsoft"));
        box.addView(edge);

        final List<String> phoneKeys = new ArrayList<>();
        List<String> phoneNames = new ArrayList<>();
        phoneKeys.add("");
        phoneNames.add("Стандартный голос телефона");
        int ps = 0;
        for (PhoneVoices.Item it : phone) {
            phoneKeys.add(it.key);
            phoneNames.add(it.label);
            if (it.key.equals(r.phoneVoice)) ps = phoneKeys.size() - 1;
        }
        final Spinner phoneSp = new Spinner(this);
        phoneSp.setAdapter(adapter(phoneNames.toArray(new String[0])));
        phoneSp.setSelection(ps);
        box.addView(label("Голос телефона"));
        box.addView(phoneSp);

        final TextView pitchL = label(""), rateL = label("");
        final SeekBar pitch = new SeekBar(this), rate = new SeekBar(this);
        pitch.setMax(40);
        rate.setMax(40);
        pitch.setProgress(r.pitch + 20);
        rate.setProgress(r.rate + 20);

        // Быстро: взять голос у одного из чтецов.
        final List<Readers.Reader> readers = Readers.load(this, settings);
        List<String> rn = new ArrayList<>();
        rn.add("—");
        for (Readers.Reader x : readers) rn.add(x.name);
        final Spinner fromReader = new Spinner(this);
        fromReader.setAdapter(adapter(rn.toArray(new String[0])));
        fromReader.setOnItemSelectedListener(new Select() {
            @Override
            void selected(int pos) {
                if (pos == 0) return;
                Readers.Reader x = readers.get(pos - 1);
                edge.setSelection(EdgeTts.SVETLANA.equals(x.edge) ? 1 : 0);
                int k = phoneKeys.indexOf(x.phone);
                phoneSp.setSelection(Math.max(0, k));
                pitch.setProgress(Math.max(0, Math.min(40, x.pitch + 20)));
                rate.setProgress(Math.max(0, Math.min(40, x.rate + 20)));
            }
        });
        box.addView(label("Взять голос у чтеца"), 2);
        box.addView(fromReader, 3);
        final Runnable labels = new Runnable() {
            @Override
            public void run() {
                pitchL.setText("Высота голоса: " + signed(pitch.getProgress() - 20));
                rateL.setText("Темп: " + signed(rate.getProgress() - 20) + "%");
            }
        };
        labels.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean u) {
                labels.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        };
        pitch.setOnSeekBarChangeListener(l);
        rate.setOnSeekBarChangeListener(l);
        box.addView(pitchL);
        box.addView(pitch);
        box.addView(rateL);
        box.addView(rate);

        final CheckBox off = new CheckBox(this);
        off.setText("Читать голосом рассказчика");
        off.setChecked(r.off);
        box.addView(off);

        android.widget.Button probe = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle);
        probe.setText("▶ Проба");
        probe.setTextColor(getResources().getColor(R.color.primary));
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service == null) return;
                if (off.isChecked()) {
                    service.preview(settings.copy(), microsoftMode());
                } else {
                    service.previewRole(EDGE_IDS[edge.getSelectedItemPosition()], phoneKeys.get(phoneSp.getSelectedItemPosition()),
                            pitch.getProgress() - 20, rate.getProgress() - 20, settings.copy(), microsoftMode());
                }
            }
        });
        box.addView(probe);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setView(scroll)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int g = gender.getSelectedItemPosition();
                        r.gender = g == 0 ? 'm' : g == 1 ? 'f' : '?';
                        r.edgeVoice = EDGE_IDS[edge.getSelectedItemPosition()];
                        r.phoneVoice = phoneKeys.get(phoneSp.getSelectedItemPosition());
                        r.pitch = pitch.getProgress() - 20;
                        r.rate = rate.getProgress() - 20;
                        r.off = off.isChecked();
                        Library.get(MainActivity.this).saveCast(rolesFor, cast.values());
                        if (service != null && service.getState() != ReaderService.STOPPED) {
                            toast("Новый голос персонажа — со следующего запуска чтения");
                        }
                        showRolesDialog();
                    }
                })
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showRolesDialog();
                    }
                })
                .show();
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(getResources().getColor(R.color.ink_soft));
        v.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    /** Реплики персонажей получают голоса их ролей. */
    private void applyRoles(List<Segment> score, int[] blockStarts) {
        for (Segment seg : score) {
            if (!seg.role.equals("character") || seg.block < 0 || seg.block >= blockStarts.length) continue;
            String who = speakerAtBlock.get(blockStarts[seg.block]);
            Library.Role r = who == null ? null : cast.get(who);
            if (r == null) continue;
            seg.speaker = who;
            if (r.off) {
                seg.edgeVoice = settings.edgeNarrator;
                seg.phoneVoice = settings.systemNarrator;
            } else {
                seg.edgeVoice = r.edgeVoice;
                seg.phoneVoice = r.phoneVoice;
                seg.pitchShift = r.pitch;
                seg.rateShift = r.rate;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void startReading() {
        if (service == null) return;
        if (editing) setEditing(false);
        final Object[] r = buildScore(false);
        final List<Segment> score = (List<Segment>) r[0];
        if (score.isEmpty()) {
            toast("Нет текста для чтения");
            return;
        }
        persistText();
        if (docKey == null) docKey = Library.docKey(text.getText().toString());
        if (currentBookId != null) Shelf.get(this).markReading(currentBookId);
        askNotificationPermission();
        clearReadingSpans();
        Runnable go = new Runnable() {
            @Override
            public void run() {
                if (service == null) return;
                if (settings.roles) applyRoles(score, (int[]) r[2]);
                service.play(score, settings.copy(), docKey, title.isEmpty() ? "Текст" : title, (Integer) r[1]);
            }
        };
        if (settings.roles) ensureRoles(false, go);
        else go.run();
    }

    @Override
    public void onState(int state, boolean buffering) {
        play.setImageResource(state == ReaderService.PLAYING ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(state == ReaderService.PLAYING ? "Пауза" : "Слушать");
        if (buffering && state == ReaderService.PLAYING) now.setText("Готовлю озвучку…");
        if (state == ReaderService.PAUSED) {
            // Курсор — на слово, где остановились: «Стоп» и «▶» продолжат отсюда.
            int pos = service != null ? service.currentTextPosition() : -1;
            if (pos >= 0 && pos <= text.length()) text.setSelection(pos);
        }
        if (state == ReaderService.STOPPED) {
            clearReadingSpans();
            progress.setProgress(0);
            shownSegment = -1;
            int last = docKey == null ? -1 : Library.get(this).lastPosition(docKey);
            if (last >= 0 && last <= text.length()) text.setSelection(last);
        }
    }

    @Override
    public void onPosition(int segIndex, int word) {
        if (service == null) return;
        List<Segment> score = service.getScore();
        if (segIndex < 0 || segIndex >= score.size()) return;
        Segment s = score.get(segIndex);
        Editable e = text.getText();
        int len = e.length();
        if (segIndex != shownSegment) {
            shownSegment = segIndex;
            progress.setProgress((int) (1000L * (segIndex + 1) / score.size()));
            now.setText(s.speaker != null ? s.speaker + ": " + s.text : s.text);
            if (s.srcStart >= 0 && s.srcEnd <= len) {
                // Серым — только недавно прочитанное (с начала абзаца, не дальше ~2000 знаков):
                // перекрашивать всю книгу на каждой фразе было бы медленно.
                int from = s.srcStart > READ_WINDOW ? android.text.TextUtils.indexOf(e, '\n', s.srcStart - READ_WINDOW) : 0;
                if (from < 0 || from > s.srcStart) from = Math.max(0, s.srcStart - READ_WINDOW);
                e.setSpan(readSpan, from, s.srcStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                e.setSpan(sentenceSpan, s.srcStart, s.srcEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (word >= 0 && word < s.wordStart.length) {
            int ws = s.wordStart[word], we = s.wordEnd[word];
            if (ws >= 0 && we <= len && we > ws) {
                e.setSpan(wordSpan, ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                follow(ws);
            }
        } else if (s.srcStart >= 0) {
            follow(s.srcStart);
        }
    }

    /** Плавно держит читаемую строку в верхней трети экрана. */
    private void follow(int offset) {
        if (!settings.follow || editing) return;
        if (System.currentTimeMillis() - userTouchAt < FOLLOW_PAUSE_AFTER_TOUCH) return;
        Layout layout = text.getLayout();
        if (layout == null || text.getHeight() == 0) return;
        int line = layout.getLineForOffset(Math.min(offset, text.length()));
        int y = layout.getLineTop(line);
        int h = text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom();
        int top = text.getScrollY();
        if (y >= top + h * 0.12 && layout.getLineBottom(line) <= top + h * 0.7) return;
        int max = Math.max(0, layout.getHeight() - h);
        int target = Math.max(0, Math.min(max, y - h / 3));
        if (scroller != null) scroller.cancel();
        scroller = ObjectAnimator.ofInt(text, "scrollY", top, target);
        scroller.setDuration(Math.abs(target - top) > h ? 250 : 500);
        scroller.start();
    }

    private void clearReadingSpans() {
        Editable e = text.getText();
        e.removeSpan(readSpan);
        e.removeSpan(sentenceSpan);
        e.removeSpan(wordSpan);
        now.setText("Поставьте курсор, откуда читать, и нажмите ▶");
    }

    @Override
    public void onTaskProgress(int done, int total) {
        progress.setProgress((int) (1000L * done / Math.max(total, 1)));
        now.setText((service != null && service.getState() == ReaderService.PREPARING ? "Готовлю для офлайна: " : "Сохраняю: ")
                + done + " из " + total);
    }

    @Override
    public void onMessage(String msg, boolean error) {
        if (error && !isFinishing()) {
            new AlertDialog.Builder(this).setTitle("Чтец").setMessage(msg).setPositiveButton("OK", null).show();
        } else {
            toast(msg);
        }
        status.setText(msg);
    }

    @Override
    public void onVoicesReady() {
        if (settingsDialogRefresh != null) settingsDialogRefresh.run();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
    }

    // ------------------------------------------------------------ меню «ещё»

    private void showMenu() {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.more));
        menu.getMenu().add(0, 1, 0, "Читать с начала");
        menu.getMenu().add(0, 2, 0, "Сохранить озвучку в файл…");
        menu.getMenu().add(0, 3, 0, "Подготовить книгу для офлайна");
        menu.getMenu().add(0, 4, 0, "Проверить голоса и связь");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (service == null) return true;
                if (item.getItemId() == 1) {
                    service.stop();
                    text.setSelection(0);
                    text.scrollTo(0, 0);
                    startReading();
                } else if (item.getItemId() == 2) {
                    saveToFile();
                } else if (item.getItemId() == 3) {
                    prepareOffline();
                } else if (item.getItemId() == 4) {
                    runDiagnostics();
                }
                return true;
            }
        });
        menu.show();
    }

    @SuppressWarnings("unchecked")
    private void saveToFile() {
        List<Segment> score = (List<Segment>) buildScore(true)[0];
        if (score.isEmpty()) {
            toast("Нет текста для чтения");
            return;
        }
        pendingSave = score;
        pendingSaveEngine = service.engineFor(score, settings);
        boolean mp3 = Settings.ENGINE_EDGE.equals(pendingSaveEngine);
        String base = title.isEmpty() ? "озвучка" : title.replaceAll("\\.[^.]*$", "");
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mp3 ? "audio/mpeg" : "audio/wav")
                .putExtra(Intent.EXTRA_TITLE, base + (mp3 ? ".mp3" : ".wav"));
        startActivityForResult(i, REQ_SAVE);
    }

    @SuppressWarnings("unchecked")
    private void prepareOffline() {
        if (Settings.ENGINE_SYSTEM.equals(settings.engine)) {
            onMessage("Голос телефона и так работает без интернета. Подготовка нужна для голоса Microsoft — "
                    + "выберите его или «Авто» в настройках голоса.", true);
            return;
        }
        final List<Segment> score = (List<Segment>) buildScore(true)[0];
        if (score.isEmpty()) {
            toast("Нет текста для чтения");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Подготовить для офлайна")
                .setMessage("Вся книга будет озвучена голосом Microsoft и сохранена в телефоне (" + score.size()
                        + " фраз, примерно " + Math.max(1, score.size() / 40) + " МБ). Потом её можно слушать без интернета. "
                        + "Нужен интернет на время подготовки — лучше Wi-Fi.")
                .setPositiveButton("Подготовить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        askNotificationPermission();
                        service.prepareOffline(score, settings.copy(), title.isEmpty() ? "Текст" : title);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------ закладки

    private String snippetAt(int pos) {
        String all = text.getText().toString();
        pos = Math.max(0, Math.min(pos, all.length()));
        int end = Math.min(all.length(), pos + 70);
        String s = all.substring(pos, end).replace('\n', ' ').trim();
        if (end < all.length()) {
            int sp = s.lastIndexOf(' ');
            if (sp > 30) s = s.substring(0, sp);
            s += "…";
        }
        return s;
    }

    private void addBookmark() {
        if (text.length() == 0) {
            toast("Сначала откройте текст");
            return;
        }
        int st = service != null ? service.getState() : ReaderService.STOPPED;
        int pos = (st == ReaderService.PLAYING || st == ReaderService.PAUSED) ? service.currentTextPosition() : text.getSelectionStart();
        if (pos < 0) pos = 0;
        // Закладка — с начала слова.
        String all = text.getText().toString();
        while (pos > 0 && pos <= all.length() && Character.isLetterOrDigit(all.charAt(pos - 1))) pos--;
        if (docKey == null) docKey = Library.docKey(all);
        final int at = pos;
        final String snippet = snippetAt(pos);
        final EditText note = new EditText(this);
        note.setHint("Ваша заметка (необязательно)");
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle("Закладка")
                .setMessage("«" + snippet + "»")
                .setView(withPadding(note))
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Library.get(MainActivity.this).addBookmark(docKey, at, snippet, note.getText().toString().trim(), title);
                        refreshBookmarkMarks();
                        toast("Закладка сохранена");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showBookmarks() {
        if (docKey == null) docKey = Library.docKey(text.getText().toString());
        final Library lib = Library.get(this);
        final List<Library.Bookmark> list = lib.bookmarks(docKey);
        final int last = lib.lastPosition(docKey);
        final List<Integer> positions = new ArrayList<>();
        List<String> items = new ArrayList<>();
        if (last > 0 && last < text.length()) {
            items.add("▶  Где вы остановились\n«" + snippetAt(last) + "»");
            positions.add(-1);
        }
        for (int i = 0; i < list.size(); i++) {
            Library.Bookmark b = list.get(i);
            String date = DateFormat.format("d MMM, HH:mm", b.created).toString();
            String line = "🔖  «" + b.snippet + "»";
            if (!b.note.isEmpty()) line += "\n✎ " + b.note;
            items.add(line + "\n" + date);
            positions.add(i);
        }
        if (items.isEmpty()) {
            onMessage("Закладок пока нет.\n\nВо время чтения нажмите 🔖 внизу — место запомнится, и к нему можно записать заметку. "
                    + "А место, где вы остановились, запоминается само.", true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Закладки")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int idx = positions.get(which);
                        if (idx < 0) goTo(last, false);
                        else bookmarkActions(list.get(idx));
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void bookmarkActions(final Library.Bookmark b) {
        String[] actions = {"Перейти", "Перейти и читать", "Изменить заметку", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle("«" + b.snippet + "»")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) goTo(b.pos, false);
                        else if (which == 1) goTo(b.pos, true);
                        else if (which == 2) editNote(b);
                        else deleteBookmark(b);
                    }
                })
                .show();
    }

    private void editNote(final Library.Bookmark b) {
        final EditText note = new EditText(this);
        note.setText(b.note);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle("Заметка")
                .setView(withPadding(note))
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Library.get(MainActivity.this).updateNote(docKey, b.id, note.getText().toString().trim());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteBookmark(final Library.Bookmark b) {
        new AlertDialog.Builder(this)
                .setMessage("Удалить закладку «" + b.snippet + "»?")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Library.get(MainActivity.this).deleteBookmark(docKey, b.id);
                        refreshBookmarkMarks();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void goTo(int pos, boolean read) {
        pos = Math.max(0, Math.min(pos, text.length()));
        boolean wasReading = service != null
                && (service.getState() == ReaderService.PLAYING || service.getState() == ReaderService.PAUSED);
        if (service != null) service.stop();
        text.setSelection(pos);
        userTouchAt = 0;
        follow(pos);
        if (read || wasReading) startReading();
    }

    /** Места закладок подсвечены в тексте сиреневым. */
    private void refreshBookmarkMarks() {
        Editable e = text.getText();
        for (Object span : bookmarkSpans) e.removeSpan(span);
        bookmarkSpans.clear();
        if (docKey == null) return;
        String all = e.toString();
        for (Library.Bookmark b : Library.get(this).bookmarks(docKey)) {
            int s = Math.min(b.pos, all.length());
            int end = s;
            while (end < all.length() && end - s < 40 && Character.isLetterOrDigit(all.charAt(end))) end++;
            if (end == s) end = Math.min(all.length(), s + 1);
            if (end <= s) continue;
            BackgroundColorSpan span = new BackgroundColorSpan(COLOR_BOOKMARK);
            e.setSpan(span, s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bookmarkSpans.add(span);
        }
    }

    private View withPadding(View v) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 2, p, 0);
        f.addView(v);
        return f;
    }

    // ------------------------------------------------------------ настройки

    private Runnable settingsDialogRefresh;

    private void showSettings() {
        final View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        final Spinner engine = (Spinner) v.findViewById(R.id.engine);
        final Spinner edgeCharacter = (Spinner) v.findViewById(R.id.edgeCharacter);
        final Spinner sysCharacter = (Spinner) v.findViewById(R.id.systemCharacter);
        final View edgeBox = v.findViewById(R.id.edgeBox), systemBox = v.findViewById(R.id.systemBox);
        final TextView problem = (TextView) v.findViewById(R.id.systemProblem);
        final TextView engineInfo = (TextView) v.findViewById(R.id.systemEngineInfo);
        final TextView fontLabel = (TextView) v.findViewById(R.id.fontLabel);
        final TextView cacheInfo = (TextView) v.findViewById(R.id.cacheInfo);
        final SeekBar font = (SeekBar) v.findViewById(R.id.fontSize);
        final CheckBox expressive = (CheckBox) v.findViewById(R.id.expressive);
        final CheckBox follow = (CheckBox) v.findViewById(R.id.follow);

        final String[] engines = {Settings.ENGINE_AUTO, Settings.ENGINE_EDGE, Settings.ENGINE_SYSTEM};
        engine.setAdapter(adapter(new String[]{
                "Авто: Microsoft, а без интернета — голос телефона",
                "Только голос Microsoft (лучше, нужен интернет)",
                "Только голос телефона (без интернета)"}));
        int engineSel = 0;
        for (int i = 0; i < engines.length; i++) if (engines[i].equals(settings.engine)) engineSel = i;
        engine.setSelection(engineSel);
        final Runnable showBoxes = new Runnable() {
            @Override
            public void run() {
                edgeBox.setVisibility(Settings.ENGINE_SYSTEM.equals(settings.engine) ? View.GONE : View.VISIBLE);
                systemBox.setVisibility(Settings.ENGINE_EDGE.equals(settings.engine) ? View.GONE : View.VISIBLE);
            }
        };
        showBoxes.run();
        engine.setOnItemSelectedListener(new Select() {
            @Override
            void selected(int pos) {
                settings.engine = engines[pos];
                showBoxes.run();
            }
        });

        // Голос Microsoft для персонажей без своей роли
        List<String> chars = new ArrayList<>();
        chars.add("Голос чтеца, чуть живее");
        int cSel = 0;
        for (int i = 0; i < EDGE_IDS.length; i++) {
            chars.add(EDGE_NAMES[i]);
            if (EDGE_IDS[i].equals(settings.edgeCharacter)) cSel = i + 1;
        }
        edgeCharacter.setAdapter(adapter(chars.toArray(new String[0])));
        edgeCharacter.setSelection(cSel);
        edgeCharacter.setOnItemSelectedListener(new Select() {
            @Override
            void selected(int pos) {
                settings.edgeCharacter = pos == 0 ? "" : EDGE_IDS[pos - 1];
            }
        });

        // Чтецы
        final LinearLayout readersBox = (LinearLayout) v.findViewById(R.id.readers);
        final List<Readers.Reader> readers = Readers.load(this, settings);
        final String[] active = {Readers.activeId(this)};
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                List<PhoneVoices.Item> phone = service != null ? service.getPhoneVoices() : new ArrayList<PhoneVoices.Item>();
                readersBox.removeAllViews();
                for (final Readers.Reader r : readers) {
                    readersBox.addView(readerRow(r, r.id.equals(active[0]), phone, new Runnable() {
                        @Override
                        public void run() { // выбрать
                            active[0] = r.id;
                            Readers.apply(r, settings);
                            Readers.save(MainActivity.this, readers, active[0]);
                            rebuild[0].run();
                        }
                    }, new Runnable() {
                        @Override
                        public void run() { // настроить
                            editReader(r, readers, active, rebuild[0]);
                        }
                    }));
                }
            }
        };
        rebuild[0].run();
        v.findViewById(R.id.addReader).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                Readers.Reader r = new Readers.Reader();
                r.id = "r" + System.currentTimeMillis();
                r.name = "Чтец " + (readers.size() + 1);
                editReader(r, readers, active, rebuild[0]);
            }
        });

        // Голоса телефона: все голоса всех установленных синтезаторов (Google, VoxSherpa, RHVoice…).
        if (service != null) service.refreshPhoneVoices(true, false); // подключиться ко всем, чтобы увидеть их голоса
        settingsDialogRefresh = new Runnable() {
            @Override
            public void run() {
                final List<String> keys = new ArrayList<>();
                List<String> charNames = new ArrayList<>();
                charNames.add("Голос чтеца, чуть живее");
                List<PhoneVoices.Item> items = service != null ? service.getPhoneVoices() : new ArrayList<PhoneVoices.Item>();
                final int cs = indexOfVoice(items, settings.systemCharacter);
                for (PhoneVoices.Item it : items) {
                    keys.add(it.key);
                    charNames.add(it.label);
                }
                sysCharacter.setAdapter(adapter(charNames.toArray(new String[0])));
                sysCharacter.setSelection(cs);
                // Начальный вызов слушателя (та же позиция) настройки не меняет — выбранный голос не сбросится,
                // даже если его синтезатор ещё подключается.
                sysCharacter.setOnItemSelectedListener(new Select() {
                    @Override
                    void selected(int pos) {
                        if (pos != cs) settings.systemCharacter = pos == 0 ? "" : keys.get(pos - 1);
                    }
                });

                // Состояние каждого синтезатора
                StringBuilder info = new StringBuilder();
                if (service != null) {
                    for (PhoneVoices.Engine e : service.getPhoneEngines()) {
                        if (info.length() > 0) info.append('\n');
                        info.append("• ").append(e.label).append(" — ");
                        if (e.status == 0) info.append("подключаю…");
                        else if (e.status == -1) info.append("не отвечает");
                        else {
                            int ru = e.russianVoices();
                            info.append("голосов: ").append(e.voices.size()).append(", русских: ").append(ru);
                            if (ru == 0 && !e.russianLanguage) {
                                info.append(" — чтобы читать по-русски, скачайте русский голос в самом «")
                                        .append(e.label).append("»");
                            }
                        }
                    }
                    String hidden = service.hiddenEngineLabel();
                    if (hidden != null) {
                        info.append("\n⚠ «").append(hidden).append("» выбран основным в телефоне, но производитель закрыл его ")
                                .append("для сторонних приложений (Samsung — начиная с One UI 7), поэтому его голосов здесь нет.");
                    }
                }
                engineInfo.setText(info);
                String why = service != null ? service.phoneProblem(settings) : null;
                problem.setVisibility(why != null ? View.VISIBLE : View.GONE);
                problem.setText(why);
                rebuild[0].run(); // подписи голосов телефона у чтецов
            }
        };
        settingsDialogRefresh.run();
        v.findViewById(R.id.installRhvoice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                openStore("com.github.olga_yakovleva.rhvoice.android");
            }
        });
        v.findViewById(R.id.diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                runDiagnostics();
            }
        });
        v.findViewById(R.id.ttsSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                if (service != null) {
                    openedTtsSettings = true;
                    service.openTtsSettings();
                }
            }
        });

        // Темп, высота, шрифт
        font.setProgress(settings.fontSize - 14);
        final Runnable labels = new Runnable() {
            @Override
            public void run() {
                fontLabel.setText("Размер текста: " + settings.fontSize);
            }
        };
        labels.run();
        SeekBar.OnSeekBarChangeListener seek = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                settings.fontSize = font.getProgress() + 14;
                text.setTextSize(settings.fontSize);
                labels.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        };
        font.setOnSeekBarChangeListener(seek);
        expressive.setChecked(settings.expressive);
        follow.setChecked(settings.follow);
        CompoundButton.OnCheckedChangeListener checks = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                settings.expressive = expressive.isChecked();
                settings.follow = follow.isChecked();
            }
        };
        expressive.setOnCheckedChangeListener(checks);
        follow.setOnCheckedChangeListener(checks);

        // Сохранённая озвучка
        final Runnable cache = new Runnable() {
            @Override
            public void run() {
                long mb = service != null ? service.cacheSize() / (1024 * 1024) : 0;
                cacheInfo.setText("Озвучка голосом Microsoft сохраняется в телефоне — повторно и заранее подготовленное "
                        + "играет без интернета. Сейчас занято: " + mb + " МБ (не больше 1 ГБ, старое удаляется само).");
            }
        };
        cache.run();
        v.findViewById(R.id.clearCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                if (service != null) service.clearCache();
                cache.run();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Голос и вид")
                .setView(v)
                .setPositiveButton("Готово", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        settingsDialogRefresh = null;
                        settings.save(MainActivity.this);
                        if (service != null && service.getState() != ReaderService.STOPPED) {
                            toast("Новые настройки голоса — со следующего запуска чтения");
                        }
                    }
                })
                .show();
    }

    private void openStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (RuntimeException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    /** Самопроверка на этом телефоне: строки отчёта появляются по мере проверки. */
    private void runDiagnostics() {
        if (service == null) return;
        final TextView log = new TextView(this);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        log.setPadding(p, p / 2, p, p / 2);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(log);
        final String[] report = {null};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Проверка голосов и связи")
                .setView(scroll)
                .setPositiveButton("Скопировать", null)
                .setNeutralButton("Поделиться", null)
                .setNegativeButton("Закрыть", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                String r = report[0] != null ? report[0] : log.getText().toString();
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("Отчёт Чтеца", r));
                toast("Отчёт скопирован");
            }
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                String r = report[0] != null ? report[0] : log.getText().toString();
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r);
                startActivity(Intent.createChooser(i, "Отправить отчёт"));
            }
        });
        log.setText("Проверяю… это займёт до минуты.\n\n");
        service.runDiagnostics(new ReaderService.DiagnosticsListener() {
            @Override
            public void onLine(String line) {
                log.append(line + "\n");
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }

            @Override
            public void onDone(String r) {
                report[0] = r;
                log.append("\nГотово. Нажмите «Скопировать» или «Поделиться», чтобы отправить отчёт.");
            }
        });
    }

    /** Позиция голоса в списке (0 — стандартный). Понимает и старые настройки, где было только имя голоса. */
    private static int indexOfVoice(List<PhoneVoices.Item> items, String key) {
        if (key == null || key.isEmpty()) return 0;
        for (int i = 0; i < items.size(); i++) {
            String k = items.get(i).key;
            if (k.equals(key) || (key.indexOf('|') < 0 && k.endsWith("|" + key))) return i + 1;
        }
        return 0;
    }

    /** Список для выпадающего меню: длинные названия голосов переносятся на несколько строк, а не обрезаются. */
    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                return wrapLines(super.getView(pos, convert, parent), 3);
            }

            @Override
            public View getDropDownView(int pos, View convert, ViewGroup parent) {
                return wrapLines(super.getDropDownView(pos, convert, parent), 6);
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private View wrapLines(View v, int maxLines) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setSingleLine(false);
            t.setHorizontallyScrolling(false);
            t.setMaxLines(maxLines);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int dp = (int) getResources().getDisplayMetrics().density;
            t.setPadding(t.getPaddingLeft(), 8 * dp, t.getPaddingRight(), 8 * dp);
            t.setMinHeight(44 * dp);
            ViewGroup.LayoutParams lp = t.getLayoutParams();
            if (lp != null) lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; // у пункта меню высота фиксирована
        }
        return v;
    }

    /** Строка чтеца: кружок выбора, имя, голоса; ▶ — проба, ✎ — настройка. */
    private View readerRow(final Readers.Reader r, boolean active, List<PhoneVoices.Item> phone,
                           final Runnable onSelect, final Runnable onEdit) {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 6 * dp, 0, 6 * dp);
        android.widget.RadioButton rb = new android.widget.RadioButton(this);
        rb.setChecked(active);
        rb.setClickable(false);
        row.addView(rb);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(r.name + (active ? "  — читает" : ""));
        name.setTextSize(15);
        name.setTextColor(getResources().getColor(active ? R.color.primary : R.color.ink));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView desc = new TextView(this);
        desc.setText(Readers.describe(r, phone));
        desc.setTextSize(12);
        desc.setTextColor(getResources().getColor(R.color.ink_soft));
        texts.addView(name);
        texts.addView(desc);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton probe = new ImageButton(this);
        probe.setImageResource(R.drawable.ic_play);
        probe.setColorFilter(getResources().getColor(R.color.primary));
        probe.setBackgroundResource(android.R.color.transparent);
        probe.setContentDescription("Послушать " + r.name);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                previewReader(r, microsoftMode());
            }
        });
        row.addView(probe, new LinearLayout.LayoutParams(44 * dp, 44 * dp));
        ImageButton edit = new ImageButton(this);
        edit.setImageResource(R.drawable.ic_edit);
        edit.setColorFilter(getResources().getColor(R.color.ink_soft));
        edit.setBackgroundResource(android.R.color.transparent);
        edit.setContentDescription("Настроить " + r.name);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                onEdit.run();
            }
        });
        row.addView(edit, new LinearLayout.LayoutParams(44 * dp, 44 * dp));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                onSelect.run();
            }
        });
        return row;
    }

    private void previewReader(Readers.Reader r, boolean microsoft) {
        if (service == null) return;
        Settings s = settings.copy();
        s.rate = 0;
        s.pitch = 0;
        service.previewRole(r.edge, r.phone, r.pitch, r.rate, s, microsoft);
    }

    /** Настройка чтеца: имя, голос Microsoft, голос телефона, темп, высота и проба обоих голосов. */
    private void editReader(final Readers.Reader r, final List<Readers.Reader> readers, final String[] active,
                            final Runnable done) {
        final boolean isNew = !readers.contains(r);
        final List<PhoneVoices.Item> phone = service != null ? service.getPhoneVoices() : new ArrayList<PhoneVoices.Item>();
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 8 * dp, 20 * dp, 0);

        final EditText name = new EditText(this);
        name.setText(r.name);
        name.setSingleLine(true);
        box.addView(label("Имя"));
        box.addView(name);

        final Spinner edge = new Spinner(this);
        edge.setAdapter(adapter(EDGE_NAMES));
        edge.setSelection(EdgeTts.SVETLANA.equals(r.edge) ? 1 : 0);
        box.addView(label("Голос Microsoft"));
        box.addView(edge);

        final List<String> keys = new ArrayList<>();
        List<String> names = new ArrayList<>();
        keys.add("");
        names.add("Стандартный голос телефона" + (service != null ? " (" + service.mainEngineLabel(settings) + ")" : ""));
        int ps = 0;
        for (PhoneVoices.Item it : phone) {
            keys.add(it.key);
            names.add(it.label);
            if (it.key.equals(r.phone)) ps = keys.size() - 1;
        }
        final Spinner phoneSp = new Spinner(this);
        phoneSp.setAdapter(adapter(names.toArray(new String[0])));
        phoneSp.setSelection(ps);
        box.addView(label("Голос телефона"));
        box.addView(phoneSp);

        final TextView rateL = label(""), pitchL = label("");
        final SeekBar rate = new SeekBar(this), pitch = new SeekBar(this);
        rate.setMax(100);
        pitch.setMax(60);
        rate.setProgress(r.rate + 50);
        pitch.setProgress(r.pitch + 30);
        final Runnable labels = new Runnable() {
            @Override
            public void run() {
                rateL.setText("Темп: " + signed(rate.getProgress() - 50) + "%");
                pitchL.setText("Высота голоса: " + signed(pitch.getProgress() - 30));
            }
        };
        labels.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean u) {
                labels.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        };
        rate.setOnSeekBarChangeListener(l);
        pitch.setOnSeekBarChangeListener(l);
        box.addView(rateL);
        box.addView(rate);
        box.addView(pitchL);
        box.addView(pitch);

        // Проба: каждым из двух голосов с текущими ползунками
        LinearLayout probes = new LinearLayout(this);
        probes.setOrientation(LinearLayout.HORIZONTAL);
        for (final boolean ms : new boolean[]{true, false}) {
            android.widget.Button b = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle);
            b.setText(ms ? "▶ Microsoft" : "▶ Телефон");
            b.setTextColor(getResources().getColor(R.color.primary));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    Readers.Reader t = new Readers.Reader();
                    t.edge = EDGE_IDS[edge.getSelectedItemPosition()];
                    t.phone = keys.get(phoneSp.getSelectedItemPosition());
                    t.rate = rate.getProgress() - 50;
                    t.pitch = pitch.getProgress() - 30;
                    previewReader(t, ms);
                }
            });
            probes.addView(b);
        }
        box.addView(probes);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        AlertDialog.Builder dlg = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Новый чтец" : "Чтец")
                .setView(scroll)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        r.name = n.isEmpty() ? "Чтец" : n;
                        r.edge = EDGE_IDS[edge.getSelectedItemPosition()];
                        r.phone = keys.get(phoneSp.getSelectedItemPosition());
                        r.rate = rate.getProgress() - 50;
                        r.pitch = pitch.getProgress() - 30;
                        if (isNew) {
                            readers.add(r);
                            active[0] = r.id; // новый чтец сразу читает
                        }
                        if (r.id.equals(active[0])) Readers.apply(r, settings);
                        Readers.save(MainActivity.this, readers, active[0]);
                        done.run();
                    }
                })
                .setNegativeButton("Отмена", null);
        if (!isNew && readers.size() > 1) {
            dlg.setNeutralButton("Удалить", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    readers.remove(r);
                    if (r.id.equals(active[0])) {
                        active[0] = readers.get(0).id;
                        Readers.apply(readers.get(0), settings);
                    }
                    Readers.save(MainActivity.this, readers, active[0]);
                    done.run();
                }
            });
        }
        dlg.show();
    }

    private static String signed(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    private abstract static class Select implements AdapterView.OnItemSelectedListener {
        abstract void selected(int pos);

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            selected(pos);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    // ------------------------------------------------------------ файлы

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQ_SAVE && pendingSave != null && service != null) {
            askNotificationPermission();
            service.save(pendingSave, settings.copy(), data.getData(), title.isEmpty() ? "Текст" : title, pendingSaveEngine);
            pendingSave = null;
        }
    }

    private void goToLibrary() {
        if (isTaskRoot()) startActivity(new Intent(this, LibraryActivity.class)); // пришли из другого приложения
        finish(); // чтение, если идёт, продолжается в фоне
    }

    /** Книга из библиотеки, свой текст, или файл через «Открыть с помощью» / «Поделиться». */
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String bookId = intent.getStringExtra(EXTRA_BOOK);
        if (bookId != null) {
            intent.removeExtra(EXTRA_BOOK); // при повороте экрана не открывать заново
            openBook(bookId);
            return true;
        }
        if (intent.getBooleanExtra(EXTRA_PASTE, false)) {
            intent.removeExtra(EXTRA_PASTE);
            currentBookId = null;
            showText("", "Свой текст");
            setEditing(true);
            return true;
        }
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (shared != null) {
                    currentBookId = null;
                    showText(shared.toString(), "Текст из другого приложения");
                    return true;
                }
            }
        }
        if (uri == null) return false;
        intent.setAction(null); // обработано
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // постоянный доступ дают не все источники
        }
        String path = "file".equals(uri.getScheme()) ? uri.getPath() : null;
        String name = displayName(uri);
        currentBookId = Shelf.get(this).addUri(uri.toString(), path, name, 0).id;
        loadUri(uri, name);
        return true;
    }

    private void openBook(String id) {
        Shelf.Book b = Shelf.get(this).get(id);
        if (b == null) {
            toast("Книга не найдена в библиотеке");
            return;
        }
        currentBookId = id;
        loadUri(b.path != null ? Uri.fromFile(new File(b.path)) : Uri.parse(b.uri), b.name);
    }

    private void loadUri(final Uri uri, final String name) {
        final String bookId = currentBookId;
        status.setText("Открываю " + name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = readAll(uri);
                    List<Block> blocks = TextExtractor.load(data, name);
                    final StringBuilder sb = new StringBuilder();
                    for (Block b : blocks) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(b.text);
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            currentBookId = bookId;
                            showText(sb.toString(), name);
                            if (bookId != null) Shelf.get(MainActivity.this).setOpened(bookId, docKey, text.length());
                            if (sb.length() == 0) status.setText("В файле не найден текст");
                        }
                    });
                } catch (final Throwable e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            String why = e instanceof SecurityException
                                    ? "нет доступа. Добавьте файл в библиотеку заново кнопкой ＋"
                                    : e instanceof OutOfMemoryError ? "файл слишком большой для этого телефона" : e.getMessage();
                            onMessage("Не удалось открыть «" + name + "»: " + why, true);
                        }
                    });
                }
            }
        }).start();
    }

    private void showText(String s, String newTitle) {
        if (service != null) service.stop();
        if (editing) setEditing(false);
        title = newTitle;
        fileName.setText(newTitle);
        text.setText(s);
        afterTextLoaded();
    }

    /** Новый текст: узнаём документ, ставим курсор туда, где остановились, показываем закладки. */
    private void afterTextLoaded() {
        docKey = Library.docKey(text.getText().toString());
        refreshBookmarkMarks();
        int last = Library.get(this).lastPosition(docKey);
        if (last > 0 && last < text.length()) {
            text.setSelection(last);
            status.setText("Продолжу с места, где вы остановились — нажмите ▶");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    userTouchAt = 0;
                    follow(text.getSelectionStart());
                }
            }, 300);
        } else {
            text.setSelection(0);
            text.scrollTo(0, 0);
            status.setText(text.length() > 0 ? "Нажмите ▶, чтобы слушать" : "");
        }
    }

    private byte[] readAll(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("файл недоступен");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > MAX_FILE) throw new IOException("файл слишком большой");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // у некоторых источников нет имени
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "файл";
    }

    // Последний текст хранится в телефоне — его можно дослушать после перезапуска.
    private void persistText() {
        try {
            FileOutputStream f = new FileOutputStream(new File(getFilesDir(), "last.txt"));
            try {
                f.write(text.getText().toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                f.close();
            }
            getSharedPreferences("state", MODE_PRIVATE).edit().putString("title", title)
                    .putString("book", currentBookId).apply();
        } catch (IOException ignored) {
            // не критично
        }
    }

    private void restoreText() {
        File f = new File(getFilesDir(), "last.txt");
        if (!f.exists()) return;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
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
            title = getSharedPreferences("state", MODE_PRIVATE).getString("title", "");
            currentBookId = getSharedPreferences("state", MODE_PRIVATE).getString("book", null);
            fileName.setText(title.isEmpty() ? "Чтец" : title);
            text.setText(new String(b, StandardCharsets.UTF_8));
            afterTextLoaded();
        } catch (IOException ignored) {
            // начнём с пустого
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
