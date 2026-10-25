package ru.polka;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Рамка во сне: фото на весь экран, внизу полоска — дата и погода в Омске.
 * «Полка» кладёт картинку туда, откуда ONYX берёт картинку сна.
 * Выключили рамку — наши файлы удаляются, и ONYX показывает свои картинки, как раньше.
 */
final class Frame {
    static final File DIR = new File("/data/local/assets/images");
    private static final String[] NAMES = {"standby0.png", "standby1.png", "standby2.png", "standby3.png", "standby4.png"};
    private static final int W = 758, H = 1024, STRIP = 176;
    private static final Locale RU = new Locale("ru");
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=54.99&longitude=73.37"
            + "&current_weather=true&daily=weathercode,temperature_2m_max,temperature_2m_min&timezone=Asia%2FOmsk&forecast_days=2";

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

    // ---------- Обновление: Диск, новое фото, погода ----------

    private static File photoFile(Context c) { return new File(c.getCacheDir(), "frame-photo.jpg"); }

    /** Включает Wi-Fi на время, забирает новое с Диска, берёт новое фото и погоду, рисует рамку. */
    static void refresh(Context c, boolean newPhoto) {
        Sync.Log quiet = new Sync.Log() { @Override public void say(String s) {} };
        boolean wifiWas = Sync.wifiOn(c, quiet);
        try {
            if (!Sync.online(c)) {
                // без интернета — только новая картинка
                if (newPhoto) nextArt(c);
                draw(c);
                return;
            }
            if (Yandex.loggedIn(c)) {
                try { Sync.run(c, quiet); } catch (Throwable e) { Crash.note(c, "frame sync", e); }
            }
            if (newPhoto) nextArt(c);
            try { fetchWeather(c); } catch (Throwable e) { Crash.note(c, "frame weather", e); }
        } finally {
            if (!wifiWas) Sync.wifiOff(c);
        }
        draw(c);
    }

    /** Свои картинки, встроенные в «Полку» (assets/frame): горы, небо, лес, лабиринт, карта, узоры… */
    private static String[] arts(Context c) {
        try {
            String[] a = c.getAssets().list("frame");
            return a == null ? new String[0] : a;
        } catch (Throwable e) {
            return new String[0];
        }
    }

    /** Следующая картинка — случайная, но не та же, что сейчас. */
    static void nextArt(Context c) {
        String[] a = arts(c);
        if (a.length == 0) return;
        String cur = Ui.prefs(c).getString("frame_art", "");
        Random rnd = new Random();
        String pick = a[rnd.nextInt(a.length)];
        for (int i = 0; i < 5 && a.length > 1 && pick.equals(cur); i++) pick = a[rnd.nextInt(a.length)];
        Ui.prefs(c).edit().putString("frame_art", pick).apply();
        note(c, "Картинка " + pick.replace(".png", "") + " из " + a.length + ".");
    }

    private static Bitmap loadArt(Context c) {
        String name = Ui.prefs(c).getString("frame_art", null);
        if (name == null) { nextArt(c); name = Ui.prefs(c).getString("frame_art", null); }
        if (name == null) return null;
        try {
            java.io.InputStream in = c.getAssets().open("frame/" + name);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable e) {
            Ui.prefs(c).edit().remove("frame_art").apply();
            return null;
        }
    }

    private static void note(Context c, String s) { Ui.prefs(c).edit().putString("frame_note", s).apply(); }

    private static void fetchPhoto(Context c) throws Exception {
        Yandex.Photos p = Yandex.framePhotos(c);
        if (p.items.length() == 0) {
            note(c, "Фото не нашлись. Проверено: " + p.tried);
            return;
        }
        // случайное, но не то же, что сейчас
        String lastPath = Ui.prefs(c).getString("frame_photo_path", "");
        Random rnd = new Random();
        JSONObject pick = p.items.getJSONObject(rnd.nextInt(p.items.length()));
        for (int i = 0; i < 5 && p.items.length() > 1 && pick.optString("path").equals(lastPath); i++)
            pick = p.items.getJSONObject(rnd.nextInt(p.items.length()));
        File tmp = new File(c.getCacheDir(), "frame-photo.part.jpg");
        Yandex.downloadPreview(c, pick.getString("preview"), tmp);
        File f = photoFile(c);
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new Exception("не сохранилось фото");
        Ui.prefs(c).edit().putString("frame_photo_path", pick.optString("path")).apply();
        note(c, "Фото из: " + p.source + " (" + p.items.length() + " шт.)");
    }

    private static void fetchWeather(Context c) throws Exception {
        Net.Resp r = Net.request(c, "GET", WEATHER_URL, null, null);
        if (r.code != 200) throw new Exception("погода: " + r.code);
        new JSONObject(r.body); // проверка, что пришёл JSON
        Ui.prefs(c).edit().putString("weather", r.body).putLong("weather_at", System.currentTimeMillis()).apply();
    }

    // ---------- Картинка ----------

    /** Рисует рамку стоя (как видно на экране). */
    static Bitmap picture(Context c) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        Date now = new Date();
        Bitmap photo = loadArt(c);
        if (photo != null) {
            Paint gray = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            // чуть контрастнее — e-ink съедает полутона
            float k = 1.18f, t = -0.09f * 255;
            cm.postConcat(new ColorMatrix(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0}));
            gray.setColorFilter(new ColorMatrixColorFilter(cm));
            cv.drawBitmap(photo, null, new Rect(0, 0, W, H - STRIP), gray);
            photo.recycle();
            strip(c, cv, now, H - STRIP);
        } else {
            big(c, cv, now);
        }
        return b;
    }

    /** Фото «во весь кадр»: обрезаем лишнее по краям, без полей. */
    private static Bitmap loadPhoto(Context c, int w, int h) {
        File f = photoFile(c);
        if (!f.exists() || f.length() == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (src == null) return null;
            float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            int cw = Math.round(w / s), ch = Math.round(h / s);
            int x = Math.max(0, (src.getWidth() - cw) / 2), y = Math.max(0, (src.getHeight() - ch) / 2);
            Bitmap crop = Bitmap.createBitmap(src, x, y, Math.min(cw, src.getWidth() - x), Math.min(ch, src.getHeight() - y));
            if (crop != src) src.recycle();
            return crop;
        } catch (Throwable e) {
            Crash.note(c, "frame load photo", e);
            return null;
        }
    }

    /** Нижняя полоса: дата слева, погода справа. */
    private static void strip(Context c, Canvas cv, Date now, int top) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        cv.drawRect(0, top, W, top + 4, p);
        int m = 30;
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(96);
        String day = new SimpleDateFormat("d", RU).format(now);
        cv.drawText(day, m, top + 116, p);
        float dw = p.measureText(day);
        p.setTextSize(30);
        cv.drawText(new SimpleDateFormat("MMMM", RU).format(now), m + dw + 14, top + 66, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(26);
        cv.drawText(new SimpleDateFormat("EEEE", RU).format(now), m + dw + 14, top + 106, p);

        String[] w = weather(c);
        float right = W - m;
        p.setTextSize(30);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float leftEnd = m + dw + 14 + p.measureText(new SimpleDateFormat("MMMM", RU).format(now)) + 24;
        if (w != null) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            float size = 48;
            p.setTextSize(size);
            while (p.measureText(w[0]) > right - leftEnd && size > 24) { size -= 2; p.setTextSize(size); }
            cv.drawText(w[0], right - p.measureText(w[0]), top + 72, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(23);
            cv.drawText(w[1], right - p.measureText(w[1]), top + 106, p);
            p.setTextSize(21);
            cv.drawText(w[2], right - p.measureText(w[2]), top + 136, p);
        }
        p.setTextSize(17);
        p.setColor(0xFF555555);
        String upd = "обн. " + new SimpleDateFormat("HH:mm", RU).format(now);
        int bat = battery(c);
        if (bat >= 0) upd += " · " + bat + "%";
        long wat = Ui.prefs(c).getLong("weather_at", 0);
        if (w != null && wat > 0) upd += " · погода на " + new SimpleDateFormat("HH:mm", RU).format(new Date(wat));
        cv.drawText(upd, m, top + STRIP - 18, p);
    }

    /** Без фото: крупная дата и погода на весь экран. */
    private static void big(Context c, Canvas cv, Date now) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(300);
        cv.drawText(new SimpleDateFormat("d", RU).format(now), W / 2f, 400, p);
        p.setTextSize(56);
        cv.drawText(new SimpleDateFormat("MMMM", RU).format(now), W / 2f, 490, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(40);
        cv.drawText(new SimpleDateFormat("EEEE", RU).format(now), W / 2f, 550, p);
        String[] w = weather(c);
        if (w != null) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(64);
            cv.drawText(w[0], W / 2f, 700, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(32);
            cv.drawText(w[1], W / 2f, 752, p);
            cv.drawText(w[2], W / 2f, 800, p);
        }
        p.setTextSize(20);
        p.setColor(0xFF555555);
        String upd = "обновлено " + new SimpleDateFormat("HH:mm", RU).format(now);
        int bat = battery(c);
        if (bat >= 0) upd += "  ·  заряд " + bat + "%";
        cv.drawText(upd, W / 2f, H - 40, p);
    }

    /** {«−2° пасмурно» крупно, «сегодня −6…−1°», «завтра −8…−3°, снег»} или null. */
    private static String[] weather(Context c) {
        String raw = Ui.prefs(c).getString("weather", null);
        long at = Ui.prefs(c).getLong("weather_at", 0);
        if (raw == null || System.currentTimeMillis() - at > 24L * 3600 * 1000) return null;
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject cur = j.getJSONObject("current_weather");
            JSONObject d = j.getJSONObject("daily");
            JSONArray mx = d.getJSONArray("temperature_2m_max"), mn = d.getJSONArray("temperature_2m_min"), code = d.getJSONArray("weathercode");
            String now = deg(cur.getDouble("temperature")) + "  " + sky(cur.optInt("weathercode", -1));
            String today = "сегодня " + deg(mn.getDouble(0)) + "…" + deg(mx.getDouble(0));
            String tom = mx.length() > 1 ? "завтра " + deg(mn.getDouble(1)) + "…" + deg(mx.getDouble(1)) + ", " + sky(code.optInt(1, -1)) : "";
            return new String[]{now, today, tom};
        } catch (Throwable e) {
            return null;
        }
    }

    private static String deg(double t) {
        long v = Math.round(t);
        return (v < 0 ? "−" + (-v) : (v > 0 ? "+" + v : "0")) + "°";
    }

    private static String sky(int code) {
        if (code == 0) return "ясно";
        if (code == 1) return "малооблачно";
        if (code == 2) return "облачно";
        if (code == 3) return "пасмурно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 57) return "морось";
        if (code >= 61 && code <= 67) return "дождь";
        if (code >= 71 && code <= 77) return "снег";
        if (code >= 80 && code <= 82) return "ливень";
        if (code == 85 || code == 86) return "снегопад";
        if (code >= 95) return "гроза";
        return "";
    }

    /** Рисует и кладёт картинку сна. Повёрнута на 90°, как лежат картинки ONYX. */
    static String draw(Context c) {
        try {
            Bitmap portrait = picture(c);
            Matrix mx = new Matrix();
            mx.postRotate(-90);
            Bitmap land = Bitmap.createBitmap(portrait, 0, 0, W, H, mx, true);
            portrait.recycle();
            if (!DIR.exists() && !DIR.mkdirs()) return "нет папки картинок ONYX";
            File tmp = new File(DIR, ".polka-frame.tmp");
            OutputStream os = new FileOutputStream(tmp);
            land.compress(Bitmap.CompressFormat.PNG, 95, os);
            os.close();
            land.recycle();
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

    private static int battery(Context c) {
        Intent bat = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat == null) return -1;
        int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return lvl < 0 || sc <= 0 ? -1 : lvl * 100 / sc;
    }
}
