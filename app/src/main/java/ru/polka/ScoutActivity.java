package ru.polka;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * «Разведчик» — проходит по всей доступной памяти книги ONYX,
 * ищет картинку экрана сна и проверяет, можно ли её подменить.
 * Отчёт: на экране + файл /sdcard/razvedchik.txt
 */
public class ScoutActivity extends Activity {

    /** Слова в имени файла или папки, по которым ищем картинку сна. */
    private static final Pattern KEY = Pattern.compile(
            "standby|sleep|screensaver|screen_saver|saver|suspend|shutdown|poweroff|power_off|"
                    + "boot|logo|wallpaper|lock|idle|сон|заставк",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("\\.(png|jpe?g|bmp|gif|webp)$", Pattern.CASE_INSENSITIVE);

    private TextView out;
    private LinearLayout box;
    private final StringBuilder full = new StringBuilder();
    private final List<Found> found = new ArrayList<>();
    private int screenW, screenH;
    private File backupDir;

    static class Found {
        File f; int w, h; boolean canWrite, dirWrite, sameAsScreen, byName;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.min(dm.widthPixels, dm.heightPixels);
        screenH = Math.max(dm.widthPixels, dm.heightPixels);
        backupDir = new File(Environment.getExternalStorageDirectory(), "razvedchik_backup");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 24, 24, 24);
        sv.addView(box);

        TextView title = text("РАЗВЕДЧИК", 30, true);
        box.addView(title);
        out = text("Ищу картинку сна по всей памяти…\nЭто может занять минуту.", 20, false);
        box.addView(out);
        setContentView(sv);

        new Thread(new Runnable() {
            @Override public void run() { scan(); }
        }).start();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.BLACK);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setTextSize(20);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    // ---------------- Поиск ----------------

    private void scan() {
        line("=== Разведчик " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + " ===");
        line("Модель: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        line("Прошивка: " + Build.DISPLAY);
        line("Экран: " + screenW + "x" + screenH);
        line("Память: " + Environment.getExternalStorageDirectory());
        line("");

        line("=== Настройки системы со словами сон/заставка ===");
        dumpSettings(Settings.System.CONTENT_URI, "system");
        dumpSettings(Settings.Global.CONTENT_URI, "global");
        dumpSettings(Settings.Secure.CONTENT_URI, "secure");
        line("");

        Set<String> seen = new HashSet<>();
        String[] roots = {
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                "/storage", "/mnt/sdcard", "/sdcard",
                "/data/local", "/data/local/assets", "/data/media",
                "/system/media", "/system/etc", "/system/res", "/system/usr",
                "/oem", "/vendor/media", "/cache", "/onyxconfig", "/data/onyx"
        };
        line("=== Корневые папки ===");
        for (String r : roots) {
            File f = new File(r);
            line(r + "  есть:" + yn(f.exists()) + " читать:" + yn(f.canRead()) + " писать:" + yn(f.canWrite()));
        }
        line("");
        line("=== Дерево памяти (папки, 3 уровня) ===");
        tree(Environment.getExternalStorageDirectory(), 0, 3);
        line("");

        for (String r : roots) walk(new File(r), 0, seen);

        line("=== Найденные картинки-кандидаты: " + found.size() + " ===");
        for (Found x : found) line(describe(x));

        saveReport();
        runOnUiThread(new Runnable() { @Override public void run() { showResult(); } });
    }

    private void dumpSettings(Uri uri, String name) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"name", "value"}, null, null, null);
            if (c == null) { line(name + ": нет доступа"); return; }
            while (c.moveToNext()) {
                String k = c.getString(0), v = c.getString(1);
                if (k != null && (KEY.matcher(k).find() || (v != null && KEY.matcher(v).find())))
                    line(name + "." + k + " = " + v);
            }
        } catch (Throwable e) {
            line(name + ": ошибка " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    private void tree(File dir, int depth, int max) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (!f.isDirectory()) continue;
            String[] in = f.list();
            line(indent(depth) + f.getName() + "/  (" + (in == null ? "?" : in.length) + ")");
            if (depth + 1 < max) tree(f, depth + 1, max);
        }
    }

    private static String indent(int d) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < d; i++) s.append("  ");
        return s.toString();
    }

    private void walk(File dir, int depth, Set<String> seen) {
        if (depth > 9) return;
        String key;
        try { key = dir.getCanonicalPath(); } catch (Exception e) { key = dir.getAbsolutePath(); }
        if (!seen.add(key)) return;
        if (key.startsWith("/proc") || key.startsWith("/sys") || key.startsWith("/dev")) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        boolean dirByName = KEY.matcher(dir.getName()).find();
        for (File f : list) {
            if (f.isDirectory()) {
                walk(f, depth + 1, seen);
            } else if (IMG.matcher(f.getName()).find()) {
                check(f, dirByName);
            }
        }
    }

    private void check(File f, boolean dirByName) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int w = o.outWidth, h = o.outHeight;
        boolean same = (Math.min(w, h) == screenW && Math.max(w, h) == screenH);
        boolean byName = dirByName || KEY.matcher(f.getName()).find();
        if (!byName && !same) return;
        // пропускаем то, что сами положили в резерв
        if (f.getAbsolutePath().startsWith(backupDir.getAbsolutePath())) return;
        Found x = new Found();
        x.f = f; x.w = w; x.h = h; x.sameAsScreen = same; x.byName = byName;
        x.canWrite = f.canWrite();
        File p = f.getParentFile();
        x.dirWrite = p != null && p.canWrite();
        found.add(x);
    }

    private String describe(Found x) {
        return x.f.getAbsolutePath() + "\n   " + x.w + "x" + x.h
                + (x.sameAsScreen ? "  [размер экрана]" : "")
                + (x.byName ? "  [по имени]" : "")
                + "  писать: " + (x.canWrite ? "ДА" : "нет")
                + "  папка: " + (x.dirWrite ? "ДА" : "нет");
    }

    private static String yn(boolean b) { return b ? "да" : "нет"; }

    private void line(String s) { full.append(s).append('\n'); }

    private File reportFile() { return new File(Environment.getExternalStorageDirectory(), "razvedchik.txt"); }

    private void saveReport() {
        try {
            PrintWriter pw = new PrintWriter(reportFile(), "UTF-8");
            pw.print(full);
            pw.close();
        } catch (Exception e) {
            full.append("Не удалось сохранить отчёт: ").append(e.getMessage()).append('\n');
        }
    }

    // ---------------- Результат ----------------

    private void showResult() {
        box.removeAllViews();
        box.addView(text("РАЗВЕДЧИК", 30, true));
        box.addView(text(Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · экран " + screenW + "×" + screenH, 18, false));

        // Сначала самые вероятные: и по имени, и по размеру экрана
        List<Found> best = new ArrayList<>();
        for (Found x : found) if (x.byName && x.sameAsScreen) best.add(x);
        for (Found x : found) if (x.byName && !x.sameAsScreen) best.add(x);
        for (Found x : found) if (!x.byName && x.sameAsScreen) best.add(x);

        if (best.isEmpty()) {
            box.addView(text("Картинок сна не нашёл. Сфотографируй этот экран и пришли.", 22, true));
        } else {
            box.addView(text("Нашёл " + best.size() + ". Сфотографируй экран и пришли мне.", 22, true));
            int n = 0;
            for (final Found x : best) {
                if (++n > 25) { box.addView(text("…остальные в файле razvedchik.txt", 18, false)); break; }
                box.addView(text(n + ". " + describe(x), 17, false));
                if (x.canWrite || x.dirWrite) {
                    final int num = n;
                    box.addView(button("Тест подмены №" + num, new View.OnClickListener() {
                        @Override public void onClick(View v) { testReplace(x, num); }
                    }));
                }
            }
        }
        box.addView(button("Вернуть всё как было", new View.OnClickListener() {
            @Override public void onClick(View v) { restoreAll(); }
        }));
        box.addView(text("Полный отчёт: " + reportFile().getAbsolutePath(), 16, false));
        out = text("", 20, true);
        box.addView(out);
    }

    /** Кладёт на место картинки тестовую (тот же размер и формат), оригинал — в резерв. */
    private void testReplace(Found x, int num) {
        try {
            if (!backupDir.exists()) backupDir.mkdirs();
            File bak = new File(backupDir, java.net.URLEncoder.encode(x.f.getAbsolutePath(), "UTF-8"));
            if (!bak.exists()) copy(x.f, bak);

            int w = x.w > 0 ? x.w : screenW, h = x.h > 0 ? x.h : screenH;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(w / 7f);
            c.drawText("ПОЛКА", w / 2f, h * 0.42f, p);
            p.setTextSize(w / 14f);
            c.drawText("тест №" + num + " — работает!", w / 2f, h * 0.55f, p);
            p.setTextSize(w / 22f);
            c.drawText(new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date()), w / 2f, h * 0.65f, p);

            String name = x.f.getName().toLowerCase(Locale.US);
            Bitmap.CompressFormat fmt = name.endsWith(".png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            OutputStream os = new FileOutputStream(x.f);
            bmp.compress(fmt, 95, os);
            os.close();
            say("№" + num + " подменил. Нажми кнопку питания — что на экране сна?");
        } catch (Throwable e) {
            say("№" + num + " не вышло: " + e.getMessage());
        }
    }

    private void restoreAll() {
        File[] list = backupDir.listFiles();
        if (list == null || list.length == 0) { say("Нечего возвращать — ничего не менял."); return; }
        int ok = 0;
        for (File bak : list) {
            File orig;
            try { orig = new File(java.net.URLDecoder.decode(bak.getName(), "UTF-8")); } catch (Exception e) { continue; }
            try { copy(bak, orig); ok++; } catch (Throwable e) { say("Не вернул " + orig + ": " + e.getMessage()); }
        }
        say("Вернул как было: " + ok + " из " + list.length);
    }

    private void say(String s) { if (out != null) out.setText(s); }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream os = new FileOutputStream(to);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }
}
