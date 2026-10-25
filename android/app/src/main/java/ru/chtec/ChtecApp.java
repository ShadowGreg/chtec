package ru.chtec;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Журнал ошибок: если приложение упадёт, текст ошибки сохраняется и показывается при следующем запуске —
 * его можно скопировать и прислать, чтобы понять, что случилось.
 */
public class ChtecApp extends Application {
    private static final String CRASH_FILE = "crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Чтец " + versionName() + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                            + "), " + Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println(new Date() + ", поток " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(new File(getFilesDir(), CRASH_FILE));
                    try {
                        out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                } catch (Throwable ignored) {
                    // журнал не обязателен
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Если в прошлый раз было падение — показывает текст ошибки (один раз). */
    static void showLastCrash(final Activity a) {
        File f = new File(a.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return;
        final String report;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            FileInputStream in = new FileInputStream(f);
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
            report = new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        new AlertDialog.Builder(a)
                .setTitle("В прошлый раз приложение закрылось с ошибкой")
                .setMessage("Пришлите, пожалуйста, этот текст — по нему можно найти и исправить ошибку.\n\n" + report)
                .setPositiveButton("Скопировать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("Ошибка Чтеца", report));
                    }
                })
                .setNeutralButton("Поделиться", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report);
                        a.startActivity(Intent.createChooser(i, "Отправить отчёт"));
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }
}
