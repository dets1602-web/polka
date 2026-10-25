package ru.polka;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Рамка во сне: «Полка» рисует свою картинку и кладёт её туда, откуда ONYX берёт картинку сна.
 * Выключили рамку — наши файлы удаляются, и ONYX показывает свои картинки, как раньше.
 */
final class Frame {
    static final File DIR = new File("/data/local/assets/images");
    private static final String[] NAMES = {"standby0.png", "standby1.png", "standby2.png", "standby3.png", "standby4.png"};
    private static final int W = 758, H = 1024;
    private static final Locale RU = new Locale("ru");

    private Frame() {}

    static boolean enabled(Context c) { return Ui.prefs(c).getBoolean("frame", false); }

    static void enable(Context c) {
        Ui.prefs(c).edit().putBoolean("frame", true).apply();
        draw(c);
        schedule(c);
    }

    static void disable(Context c) {
        Ui.prefs(c).edit().putBoolean("frame", false).apply();
        cancel(c);
        for (String n : NAMES) new File(DIR, n).delete();
    }

    // ---------- Будильник раз в час ----------

    private static PendingIntent tick(Context c) {
        Intent i = new Intent(c, FrameReceiver.class).setAction(FrameReceiver.ACTION);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, tick(c));
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tick(c));
    }

    // ---------- Картинка ----------

    /** Рисует рамку стоя (как видно на экране). */
    static Bitmap picture(Context c) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        Date now = new Date();
        int m = 44;

        // Дата крупно
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(190);
        String day = new SimpleDateFormat("d", RU).format(now);
        cv.drawText(day, m - 8, 230, p);
        float dw = p.measureText(day);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(42);
        cv.drawText(new SimpleDateFormat("MMMM", RU).format(now), m + dw + 16, 150, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(34);
        cv.drawText(new SimpleDateFormat("EEEE", RU).format(now), m + dw + 16, 200, p);
        cv.drawRect(m, 262, W - m, 268, p);

        // Что нового: файлы одной папки — одной строкой; список не залезает на «Читаю сейчас»
        String last = Ui.prefs(c).getString("last", null);
        boolean reading = last != null && new File(last).exists();
        int limit = reading ? H - 250 : H - 90;
        int y = 330;
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(30);
        List<String[]> fresh = freshEntries(c);
        cv.drawText(fresh.isEmpty() ? "Нового на полках нет" : "Новое на полках", m, y, p);
        y += 20;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(28);
        Paint g = new Paint(p);
        g.setColor(0xFF555555);
        g.setTextSize(21);
        int shownCount = 0;
        for (int i = 0; i < fresh.size(); i++) {
            String[] e = fresh.get(i);
            int reserve = i + 1 < fresh.size() ? 44 : 0;
            if (y + 52 + 30 + reserve > limit) break;
            y += 52;
            cv.drawRect(m, y - 20, m + 12, y - 8, p);
            cv.drawText(fit(p, e[0], W - 2 * m - 30), m + 30, y, p);
            y += 30;
            cv.drawText(fit(g, e[1], W - 2 * m - 30), m + 30, y, g);
            shownCount++;
        }
        if (shownCount < fresh.size()) {
            cv.drawText("…и ещё " + (fresh.size() - shownCount), m + 30, y + 40, g);
        }

        // Читаю сейчас
        if (reading) {
            int by = H - 230;
            Paint box = new Paint();
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(4);
            box.setColor(Color.BLACK);
            cv.drawRect(m, by, W - m, by + 110, box);
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            cv.drawText("ЧИТАЮ СЕЙЧАС", m + 20, by + 36, p);
            p.setTextSize(28);
            cv.drawText(fit(p, Files.title(new File(last)), W - 2 * m - 40), m + 20, by + 80, p);
        }

        // Низ: время обновления и заряд
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(22);
        p.setColor(0xFF444444);
        String upd = "обновлено " + new SimpleDateFormat("HH:mm", RU).format(now);
        int bat = battery(c);
        if (bat >= 0) upd += "  ·  заряд " + bat + "%";
        cv.drawText(upd, m, H - 44, p);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        String brand = "ПОЛКА";
        cv.drawText(brand, W - m - p.measureText(brand), H - 44, p);
        return b;
    }

    /** Рисует и кладёт картинку сна. Повёрнута на 90°, как лежат картинки ONYX. */
    static String draw(Context c) {
        try {
            Bitmap portrait = picture(c);
            Matrix mx = new Matrix();
            mx.postRotate(-90);
            Bitmap land = Bitmap.createBitmap(portrait, 0, 0, W, H, mx, true);
            if (!DIR.exists() && !DIR.mkdirs()) return "нет папки картинок ONYX";
            File tmp = new File(DIR, ".polka-frame.tmp");
            OutputStream os = new FileOutputStream(tmp);
            land.compress(Bitmap.CompressFormat.PNG, 95, os);
            os.close();
            for (String n : NAMES) {
                File f = new File(DIR, n);
                copy(tmp, f);
                // ONYX рисует сон от другого пользователя — файл должен быть ему читаем
                f.setReadable(true, false);
            }
            tmp.delete();
            Ui.prefs(c).edit().putString("frame_when", new SimpleDateFormat("d MMM HH:mm", RU).format(new Date())).apply();
            return null;
        } catch (Throwable e) {
            Crash.note(c, "frame draw", e);
            return e.getMessage();
        }
    }

    private static void copy(File from, File to) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(from);
        OutputStream os = new FileOutputStream(to);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }

    /** Новое на полках: {заголовок, подпись}. Несколько новых файлов в одной папке — одна строка. */
    private static List<String[]> freshEntries(Context c) {
        List<File> files = freshFiles(c);
        java.util.LinkedHashMap<String, int[]> groups = new java.util.LinkedHashMap<>();
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> shelfRoots = new java.util.HashSet<>();
        for (String sh : Files.SHELVES) shelfRoots.add(Ui.folder(sh).getAbsolutePath());
        for (File f : files) {
            File parent = f.getParentFile();
            String key = parent == null ? "" : parent.getAbsolutePath();
            if (shelfRoots.contains(key)) {
                out.add(new String[]{Files.title(f), shelfOf(f)});
            } else if (groups.containsKey(key)) {
                groups.get(key)[0]++;
            } else {
                groups.put(key, new int[]{1, out.size()});
                out.add(null); // место группы — по самому свежему файлу
            }
        }
        for (java.util.Map.Entry<String, int[]> e : groups.entrySet()) {
            File dir = new File(e.getKey());
            int n = e.getValue()[0];
            String sub = shelfOf(dir) + (n > 1 ? " · " + Ui.plural(n, "новый файл", "новых файла", "новых файлов") : "");
            String title = n > 1 ? dir.getName() : firstFile(files, e.getKey());
            out.set(e.getValue()[1], new String[]{title, sub});
        }
        return out;
    }

    private static String firstFile(List<File> files, String dir) {
        for (File f : files) if (f.getParentFile() != null && f.getParentFile().getAbsolutePath().equals(dir)) return Files.title(f);
        return new File(dir).getName();
    }

    private static List<File> freshFiles(Context c) {
        List<File> out = new ArrayList<>();
        for (String s : Files.SHELVES) collect(c, Ui.folder(s), out, 0);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = b.lastModified() / 300000, y = a.lastModified() / 300000;
                if (x != y) return x < y ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    private static void collect(Context c, File dir, List<File> out, int depth) {
        File[] list = dir.listFiles();
        if (list == null || depth > 5) return;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collect(c, f, out, depth + 1);
            else if (Files.isNew(c, f)) out.add(f);
        }
    }

    private static String shelfOf(File f) {
        String path = f.getAbsolutePath();
        for (String s : Files.SHELVES) if (path.startsWith(Ui.folder(s).getAbsolutePath())) return s;
        return "";
    }

    private static String fit(Paint p, String s, float w) {
        if (p.measureText(s) <= w) return s;
        while (s.length() > 1 && p.measureText(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static int battery(Context c) {
        Intent bat = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat == null) return -1;
        int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return lvl < 0 || sc <= 0 ? -1 : lvl * 100 / sc;
    }
}
