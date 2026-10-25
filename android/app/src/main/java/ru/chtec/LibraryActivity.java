package ru.chtec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Стартовый экран: все книги и документы на телефоне, полки и свои сборки. */
public class LibraryActivity extends Activity {
    private static final int REQ_ADD = 1, REQ_PERMISSION = 2;
    private static final String ALL = "all", NEW = "new";

    private Shelf shelf;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ListView list;
    private LinearLayout chips;
    private EditText search;
    private TextView empty, scanStatus, scanButton, continueTitle, continueInfo;
    private View scanBox, continueCard;
    private final BookAdapter adapter = new BookAdapter();
    private final List<Shelf.Book> shown = new ArrayList<>();
    private String filter = ALL;
    private AtomicBoolean scanCancel;
    private boolean scanAfterPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_library);
        shelf = Shelf.get(this);
        list = (ListView) findViewById(R.id.list);
        chips = (LinearLayout) findViewById(R.id.chips);
        search = (EditText) findViewById(R.id.search);
        empty = (TextView) findViewById(R.id.empty);
        scanStatus = (TextView) findViewById(R.id.scanStatus);
        scanButton = (TextView) findViewById(R.id.scan);
        scanBox = findViewById(R.id.scanBox);
        continueCard = findViewById(R.id.continueCard);
        continueTitle = (TextView) findViewById(R.id.continueTitle);
        continueInfo = (TextView) findViewById(R.id.continueInfo);

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                open(shown.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                bookActions(shown.get(pos));
                return true;
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshList();
            }
        });
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onScanPressed();
            }
        });
        findViewById(R.id.add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_ADD);
            }
        });
        findViewById(R.id.menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        continueCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Shelf.Book b = shelf.lastOpened();
                if (b != null) open(b);
            }
        });

        ChtecApp.showLastCrash(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (scanAfterPermission) {
            scanAfterPermission = false;
            if (hasFilesAccess()) startScan();
            else toast("Без доступа к файлам поиск невозможен — добавляйте книги кнопкой +");
        }
    }

    @Override
    protected void onDestroy() {
        if (scanCancel != null) scanCancel.set(true);
        super.onDestroy();
    }

    private void refresh() {
        buildChips();
        refreshList();
    }

    // ------------------------------------------------------------ полки (вкладки)

    private void buildChips() {
        chips.removeAllViews();
        List<Shelf.Book> all = shelf.all();
        int allCount = 0, newCount = 0;
        for (Shelf.Book b : all) {
            if (!b.cols.contains(Shelf.BORING)) allCount++;
            if (b.isNew) newCount++;
        }
        addChip(ALL, "Все", allCount, false);
        if (newCount > 0 || filter.equals(NEW)) addChip(NEW, "Новые", newCount, false);
        for (Shelf.Col c : shelf.collections()) {
            int n = 0;
            for (Shelf.Book b : all) if (b.cols.contains(c.id)) n++;
            addChip(c.id, c.name, n, !isBuiltIn(c.id));
        }
        TextView plus = chip("＋ Сборка", false);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newCollection(null);
            }
        });
        chips.addView(plus);
    }

    private static boolean isBuiltIn(String id) {
        for (String b : Shelf.BUILT_IN) if (b.equals(id)) return true;
        return false;
    }

    private void addChip(final String id, final String name, int count, boolean custom) {
        TextView t = chip(name + (count > 0 ? "  " + count : ""), id.equals(filter));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = id;
                refresh();
            }
        });
        if (custom) {
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    collectionActions(id, name);
                    return true;
                }
            });
        }
        chips.addView(t);
    }

    private TextView chip(String label, boolean selected) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        int dp = (int) getResources().getDisplayMetrics().density;
        t.setPadding(14 * dp, 7 * dp, 14 * dp, 7 * dp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * dp);
        int primary = getResources().getColor(R.color.primary);
        if (selected) {
            bg.setColor(primary);
            t.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0xFFFFFFFF);
            bg.setStroke(dp, 0x33000000);
            t.setTextColor(getResources().getColor(R.color.ink));
        }
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4 * dp, 0, 4 * dp, 0);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------ список книг

    private void refreshList() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Shelf.Book b : shelf.all()) {
            boolean match;
            if (filter.equals(ALL)) match = !b.cols.contains(Shelf.BORING);
            else if (filter.equals(NEW)) match = b.isNew;
            else match = b.cols.contains(filter);
            if (match && (q.isEmpty() || b.name.toLowerCase(Locale.ROOT).contains(q))) shown.add(b);
        }
        Collections.sort(shown, new Comparator<Shelf.Book>() {
            @Override
            public int compare(Shelf.Book a, Shelf.Book b) {
                if (a.opened != b.opened) return a.opened > b.opened ? -1 : 1;     // недавно открытые — выше
                if (a.isNew != b.isNew) return a.isNew ? -1 : 1;
                return a.title().compareToIgnoreCase(b.title());
            }
        });
        adapter.notifyDataSetChanged();

        Shelf.Book last = shelf.lastOpened();
        boolean showContinue = last != null && filter.equals(ALL) && q.isEmpty();
        continueCard.setVisibility(showContinue ? View.VISIBLE : View.GONE);
        if (showContinue) {
            continueTitle.setText(last.title());
            int p = percent(last);
            continueInfo.setText(p > 0 ? "Прослушано " + p + "%" : "Открыта " + date(last.opened));
        }

        boolean nothing = shown.isEmpty();
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        list.setVisibility(nothing ? View.GONE : View.VISIBLE);
        if (nothing) {
            if (shelf.all().isEmpty()) {
                empty.setText("Здесь будут ваши книги и документы.\n\nНажмите «Новое» — я найду на телефоне все файлы, "
                        + "которые можно прочитать вслух (.doc, .docx, .md, .txt, .rtf, .bin).\n\nИли добавьте файл кнопкой ＋.");
            } else if (!q.isEmpty()) {
                empty.setText("Ничего не найдено по запросу «" + q + "»");
            } else if (filter.equals(NEW)) {
                empty.setText("Новых файлов нет. Нажмите «Новое», чтобы поискать ещё раз.");
            } else {
                empty.setText("На этой полке пока пусто.\n\nНажмите ⋮ у книги (или подержите её), чтобы положить её сюда.");
            }
        }
    }

    private int percent(Shelf.Book b) {
        if (b.docKey == null || b.textLength <= 0) return 0;
        int pos = Library.get(this).lastPosition(b.docKey);
        if (pos <= 0) return 0;
        return Math.max(1, Math.min(100, (int) (100L * pos / b.textLength)));
    }

    private String date(long t) {
        return DateFormat.format("d MMM yyyy", t).toString();
    }

    private static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " КБ";
        return String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0).replace('.', ',');
    }

    private static int badgeColor(String ext) {
        if (ext.equals("doc") || ext.equals("docx")) return 0xFF2B579A;
        if (ext.equals("md") || ext.equals("markdown")) return 0xFF37474F;
        if (ext.equals("rtf")) return 0xFF7B4FA8;
        if (ext.equals("bin")) return 0xFFB35C00;
        return 0xFF6D7A6A;
    }

    private final class BookAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(LibraryActivity.this).inflate(R.layout.item_book, parent, false);
            final Shelf.Book b = shown.get(i);
            int dp = (int) getResources().getDisplayMetrics().density;

            TextView badge = (TextView) v.findViewById(R.id.badge);
            String ext = b.ext();
            badge.setText(ext.isEmpty() ? "?" : ext.toUpperCase(Locale.ROOT));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(8 * dp);
            bg.setColor(badgeColor(ext));
            badge.setBackground(bg);

            ((TextView) v.findViewById(R.id.title)).setText(b.title());
            TextView isNew = (TextView) v.findViewById(R.id.isNew);
            isNew.setVisibility(b.isNew ? View.VISIBLE : View.GONE);
            GradientDrawable nb = new GradientDrawable();
            nb.setCornerRadius(6 * dp);
            nb.setColor(getResources().getColor(R.color.accent));
            isNew.setBackground(nb);

            String folder = "";
            if (b.path != null) {
                File parentDir = new File(b.path).getParentFile();
                if (parentDir != null) folder = parentDir.getName() + " · ";
            }
            ((TextView) v.findViewById(R.id.meta)).setText(folder + size(b.size)
                    + (b.modified > 0 ? " · " + date(b.modified) : ""));

            ProgressBar pb = (ProgressBar) v.findViewById(R.id.progress);
            int p = percent(b);
            pb.setVisibility(p > 0 ? View.VISIBLE : View.GONE);
            pb.setProgress(p * 10);

            StringBuilder cols = new StringBuilder();
            for (Shelf.Col c : shelf.collections()) {
                if (!b.cols.contains(c.id)) continue;
                if (cols.length() > 0) cols.append(" · ");
                cols.append(c.name);
            }
            if (p > 0) cols.insert(0, p + "%" + (cols.length() > 0 ? " · " : ""));
            TextView colsView = (TextView) v.findViewById(R.id.cols);
            colsView.setText(cols);
            colsView.setVisibility(cols.length() > 0 ? View.VISIBLE : View.GONE);

            v.findViewById(R.id.more).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    bookActions(b);
                }
            });
            return v;
        }
    }

    private void open(Shelf.Book b) {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_BOOK, b.id));
    }

    // ------------------------------------------------------------ полки и сборки книги

    private void bookActions(final Shelf.Book b) {
        final List<Shelf.Col> cols = shelf.collections();
        String[] names = new String[cols.size()];
        boolean[] checked = new boolean[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            names[i] = cols.get(i).name;
            checked[i] = b.cols.contains(cols.get(i).id);
        }
        new AlertDialog.Builder(this)
                .setTitle(b.title())
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean on) {
                        shelf.setInCollection(b.id, cols.get(which).id, on);
                        if (on && (cols.get(which).id.equals(Shelf.DONE) || cols.get(which).id.equals(Shelf.READING))) {
                            // «Читаю» и «Прочитано» взаимоисключающие — обновим галочки
                            d.dismiss();
                            bookActions(shelf.get(b.id));
                        }
                    }
                })
                .setPositiveButton("Готово", null)
                .setNeutralButton("Новая сборка…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        newCollection(b);
                    }
                })
                .setNegativeButton("Убрать из списка", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shelf.remove(b.id);
                        toast("Убрано из библиотеки (сам файл не удалён)");
                        refresh();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        refresh();
                    }
                })
                .show();
    }

    private void newCollection(final Shelf.Book addBook) {
        final EditText name = new EditText(this);
        name.setHint("Например: Детективы, На дачу, Учёба");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("Новая сборка")
                .setView(padded(name))
                .setPositiveButton("Создать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        Shelf.Col c = shelf.addCollection(n);
                        if (addBook != null) shelf.setInCollection(addBook.id, c.id, true);
                        filter = c.id;
                        refresh();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void collectionActions(final String id, final String currentName) {
        new AlertDialog.Builder(this)
                .setTitle(currentName)
                .setItems(new String[]{"Переименовать", "Удалить сборку"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            final EditText name = new EditText(LibraryActivity.this);
                            name.setText(currentName);
                            new AlertDialog.Builder(LibraryActivity.this)
                                    .setTitle("Название сборки")
                                    .setView(padded(name))
                                    .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d2, int w) {
                                            String n = name.getText().toString().trim();
                                            if (!n.isEmpty()) shelf.renameCollection(id, n);
                                            refresh();
                                        }
                                    })
                                    .setNegativeButton("Отмена", null)
                                    .show();
                        } else {
                            shelf.deleteCollection(id);
                            if (filter.equals(id)) filter = ALL;
                            toast("Сборка удалена (книги остались в библиотеке)");
                            refresh();
                        }
                    }
                })
                .show();
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 2, p, 0);
        f.addView(v);
        return f;
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Вставить свой текст");
        m.getMenu().add(0, 2, 0, "Добавить файл…");
        m.getMenu().add(0, 3, 0, "Новая сборка…");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == 1) {
                    startActivity(new Intent(LibraryActivity.this, MainActivity.class).putExtra(MainActivity.EXTRA_PASTE, true));
                } else if (item.getItemId() == 2) {
                    findViewById(R.id.add).performClick();
                } else {
                    newCollection(null);
                }
                return true;
            }
        });
        m.show();
    }

    // ------------------------------------------------------------ поиск файлов на телефоне

    private boolean hasFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void onScanPressed() {
        if (scanCancel != null) {
            scanCancel.set(true); // повторное нажатие — остановить поиск
            return;
        }
        if (hasFilesAccess()) {
            startScan();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Доступ к файлам")
                    .setMessage("Чтобы найти книги и документы по всему телефону, Android просит дать приложению "
                            + "«Доступ ко всем файлам». Сейчас откроются настройки — включите переключатель и вернитесь назад.\n\n"
                            + "Приложение только читает файлы и ничего не отправляет.")
                    .setPositiveButton("Открыть настройки", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            scanAfterPermission = true;
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQ_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
        else toast("Без доступа к файлам поиск невозможен — добавляйте книги кнопкой +");
    }

    private void startScan() {
        final AtomicBoolean cancel = new AtomicBoolean();
        scanCancel = cancel;
        scanBox.setVisibility(View.VISIBLE);
        scanStatus.setText("Ищу книги и документы…");
        scanButton.setText("Стоп");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<File> found = Scanner.scan(LibraryActivity.this, new Scanner.Progress() {
                    @Override
                    public void onProgress(final int folders, final int count) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                scanStatus.setText("Ищу… просмотрено папок: " + folders + ", найдено файлов: " + count);
                            }
                        });
                    }
                }, cancel);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        scanCancel = null;
                        scanBox.setVisibility(View.GONE);
                        scanButton.setText("Новое");
                        if (cancel.get() && isFinishing()) return;
                        int fresh = shelf.applyScan(found);
                        if (fresh > 0) {
                            filter = NEW;
                            toast("Найдено новых: " + fresh);
                        } else {
                            toast(cancel.get() ? "Поиск остановлен" : "Новых файлов нет. Всего найдено: " + found.size());
                        }
                        refresh();
                    }
                });
            }
        }, "chtec-scan").start();
    }

    // ------------------------------------------------------------ добавление вручную

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_ADD || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Shelf.Book lastAdded = null;
        for (Uri u : uris) {
            try {
                // Разрешение на файл сохраняется, чтобы открывать его из библиотеки и после перезапуска.
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // источник не даёт постоянный доступ — откроется, пока приложение запущено
            }
            String name = "файл";
            long size = 0;
            Cursor c = null;
            try {
                c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null);
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0);
                    if (!c.isNull(1)) size = c.getLong(1);
                }
            } catch (RuntimeException ignored) {
                // без имени
            } finally {
                if (c != null) c.close();
            }
            lastAdded = shelf.addUri(u.toString(), null, name, size);
        }
        refresh();
        if (uris.size() == 1 && lastAdded != null) open(lastAdded);
        else if (uris.size() > 1) toast("Добавлено файлов: " + uris.size());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
