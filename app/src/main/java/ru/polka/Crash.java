package ru.polka;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Если «Полка» падает — пишет причину в память книги: polka-crash.txt.
 * Этот файл можно прочитать с компьютера и понять, что сломалось.
 */
final class Crash {
    private static boolean installed;

    private Crash() {}

    private static File file() {
        return new File(Environment.getExternalStorageDirectory(), "polka-crash.txt");
    }

    static synchronized void install(final Context c) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                note(c, "ПАДЕНИЕ в потоке " + t.getName(), e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** Дописывает ошибку в файл (не больше ~200 КБ, потом начинает заново). */
    static synchronized void note(Context c, String where, Throwable e) {
        try {
            File f = file();
            boolean append = f.length() < 200 * 1024;
            PrintWriter pw = new PrintWriter(new FileWriter(f, append));
            String ver = "?";
            try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
            pw.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " · Полка " + ver + " · Android " + Build.VERSION.RELEASE + " · " + where);
            e.printStackTrace(pw);
            pw.println();
            pw.close();
        } catch (Throwable ignored) {}
    }
}
