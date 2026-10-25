package ru.polka;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Забирает новое с Яндекс Диска: disk:/Полка/<полка>/… → полки книги.
 * Только скачивает: на книге ничего не удаляет и на Диске ничего не меняет.
 */
final class Sync {
    static final String ROOT = "disk:/Полка";
    private static final long MAX_FILE = 300L * 1024 * 1024;

    interface Log { void say(String s); }

    private Sync() {}

    static final class Result {
        int added, updated, skipped, errors;
        String note = "";
    }

    /** Включает Wi-Fi, если он выключен, и ждёт подключения. Возвращает, был ли он включён до нас. */
    static boolean wifiOn(Context c, Log log) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean was = wm != null && wm.isWifiEnabled();
        if (wm != null && !was) {
            log.say("Включаю Wi-Fi…");
            wm.setWifiEnabled(true);
        }
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (int i = 0; i < 45; i++) {
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) return was;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        return was;
    }

    static void wifiOff(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) wm.setWifiEnabled(false);
    }

    static Result run(Context c, Log log) {
        Result r = new Result();
        boolean wifiWas = wifiOn(c, log);
        try {
            JSONArray top = Yandex.list(c, ROOT);
            if (top == null) {
                r.note = "На Диске нет папки «Полка». Создайте её в корне Диска, внутри — «Книги», «Схемы», «Документация».";
                return r;
            }
            // Что лежит в «Полке» на Диске: папки разносим по полкам (регистр букв не важен),
            // незнакомые папки и файлы прямо в «Полке» — на полку «Книги», чтобы ничего не потерялось
            StringBuilder seen = new StringBuilder();
            JSONArray loose = new JSONArray();
            for (int i = 0; i < top.length(); i++) {
                JSONObject it = top.getJSONObject(i);
                String name = it.optString("name");
                if (name.startsWith(".")) continue;
                if (!"dir".equals(it.optString("type"))) { loose.put(it); continue; }
                String shelf = shelfFor(name);
                File dest = shelf != null ? Ui.folder(shelf) : new File(Ui.folder("Книги"), name);
                seen.append(seen.length() == 0 ? "" : ", ").append(name);
                log.say("Смотрю «" + name + "»…");
                JSONArray items = Yandex.list(c, it.optString("path"));
                if (items != null) walk(c, items, dest, r, log, 0);
            }
            if (loose.length() > 0) walk(c, loose, Ui.folder("Книги"), r, log, 0);

            int total = r.added + r.updated + r.skipped + r.errors;
            if (r.added + r.updated > 0) {
                r.note = "Новых: " + r.added + (r.updated > 0 ? ", обновлено: " + r.updated : "") + ".";
            } else if (total == 0) {
                r.note = "В «Полке» на Диске нет файлов." + (seen.length() > 0 ? " Папки: " + seen + "." : "");
            } else {
                r.note = "Нового нет: все " + total + " файл(ов) уже на книге.";
            }
            if (r.errors > 0) r.note += " Не скачалось: " + r.errors + ".";
        } catch (Throwable e) {
            r.note = "Ошибка: " + e.getMessage();
            r.errors++;
        } finally {
            if (!wifiWas) wifiOff(c);
            String when = new SimpleDateFormat("d MMM HH:mm", new Locale("ru")).format(new Date());
            Ui.prefs(c).edit().putString("sync_when", when).putString("sync_note", r.note).apply();
        }
        return r;
    }

    /** Папка на Диске → полка книги. null — незнакомое имя. */
    private static String shelfFor(String name) {
        String n = name.trim().toLowerCase(new Locale("ru"));
        if (n.startsWith("книг")) return "Книги";
        if (n.startsWith("схем")) return "Схемы";
        if (n.startsWith("докум") || n.startsWith("инструк")) return "Документация";
        return null;
    }

    private static void walk(Context c, JSONArray items, File dir, Result r, Log log, int depth) throws Exception {
        if (depth > 6) return;
        if (!dir.exists()) dir.mkdirs();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String name = it.optString("name");
            if (name.length() == 0 || name.startsWith(".")) continue;
            if ("dir".equals(it.optString("type"))) {
                JSONArray sub = Yandex.list(c, it.optString("path"));
                if (sub != null) walk(c, sub, new File(dir, name), r, log, depth + 1);
                continue;
            }
            long size = it.optLong("size", -1);
            File local = new File(dir, name);
            if (local.exists() && local.length() == size) { r.skipped++; continue; }
            if (size > MAX_FILE) { r.errors++; continue; }
            boolean existed = local.exists();
            log.say("Скачиваю: " + name);
            try {
                String url = it.optString("file", "");
                if (url.length() == 0) url = Yandex.downloadUrl(c, it.optString("path"));
                Net.download(c, url, local);
                if (existed) r.updated++; else r.added++;
            } catch (Throwable e) {
                r.errors++;
                log.say("Не скачалось " + name + ": " + e.getMessage());
            }
        }
    }
}
