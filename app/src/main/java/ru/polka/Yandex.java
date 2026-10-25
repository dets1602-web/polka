package ru.polka;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Вход в Яндекс по коду (как на телевизоре): книга показывает код,
 * человек вводит его на телефоне на ya.ru/device. Пароль книга не видит.
 * Токен хранится только во внутренней памяти «Полки», доступ — только чтение Диска.
 */
final class Yandex {
    /** Номер приложения «Полка» в Яндекс OAuth — он не секретный. */
    static final String CLIENT_ID = "8cee5f8c6ddd4fc6af5699c169737d90";

    private static final String OAUTH = "https://oauth.yandex.ru";
    private static final String API = "https://cloud-api.yandex.net/v1/disk";

    private Yandex() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("yandex", Context.MODE_PRIVATE);
    }

    static boolean configured() { return CLIENT_ID.length() > 0; }

    static boolean loggedIn(Context c) { return prefs(c).getString("access", null) != null; }

    static void logout(Context c) { prefs(c).edit().remove("access").remove("refresh").remove("expires").apply(); }

    // ---------- Секрет приложения: только на книге, не в коде ----------

    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile("[0-9a-fA-F]{32}");

    /** Файлы, куда можно положить секрет с компьютера. Прочитав, «Полка» их удаляет. */
    private static java.io.File[] secretFiles() {
        java.io.File sd = android.os.Environment.getExternalStorageDirectory();
        return new java.io.File[]{
                new java.io.File(sd, "polka-secret.txt"),
                new java.io.File(sd, "Download/polka-secret.txt"),
                new java.io.File(sd, "polka-secret.txt.txt")
        };
    }

    /** Секрет из памяти «Полки»; если его нет — пробует забрать из файла. */
    static String secret(Context c) {
        String s = prefs(c).getString("secret", null);
        if (s != null) return s;
        for (java.io.File f : secretFiles()) {
            if (!f.exists()) continue;
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                StringBuilder all = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) all.append(ln).append(' ');
                r.close();
                java.util.regex.Matcher m = SECRET.matcher(all);
                if (m.find() && setSecret(c, m.group())) {
                    f.delete();
                    return m.group();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static boolean setSecret(Context c, String s) {
        if (s == null) return false;
        s = s.trim();
        if (!SECRET.matcher(s).matches()) return false;
        prefs(c).edit().putString("secret", s.toLowerCase(java.util.Locale.US)).apply();
        return true;
    }

    private static String secretParam(Context c) {
        String s = secret(c);
        return s == null ? "" : "&client_secret=" + Net.enc(s);
    }

    // ---------- Вход по коду ----------

    static final class Code {
        String deviceCode, userCode, url;
        int interval;
        long until;
    }

    static Code requestCode(Context c) throws Exception {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.length() < 6) id = "polka-onyx-book";
        String form = "client_id=" + Net.enc(CLIENT_ID)
                + "&device_id=" + Net.enc(id)
                + "&device_name=" + Net.enc("Книга ONYX (Полка)");
        Net.Resp r = Net.request(c, "POST", OAUTH + "/device/code", form, null);
        JSONObject j = new JSONObject(r.body.length() > 0 ? r.body : "{}");
        if (r.code != 200) throw new Exception("Яндекс: " + j.optString("error_description", j.optString("error", "код " + r.code)));
        Code k = new Code();
        k.deviceCode = j.getString("device_code");
        k.userCode = j.getString("user_code");
        k.url = j.optString("verification_url", "https://ya.ru/device");
        k.interval = Math.max(3, j.optInt("interval", 5));
        k.until = System.currentTimeMillis() + j.optInt("expires_in", 300) * 1000L;
        return k;
    }

    /** null — ещё ждём; "" — вошли; иначе текст ошибки. */
    static String poll(Context c, Code k) throws Exception {
        String form = "grant_type=device_code&code=" + Net.enc(k.deviceCode) + "&client_id=" + Net.enc(CLIENT_ID) + secretParam(c);
        Net.Resp r = Net.request(c, "POST", OAUTH + "/token", form, null);
        JSONObject j = new JSONObject(r.body.length() > 0 ? r.body : "{}");
        if (r.code == 200 && j.has("access_token")) {
            save(c, j);
            return "";
        }
        String err = j.optString("error", "код " + r.code);
        if (err.equals("authorization_pending") || err.equals("slow_down")) return null;
        return j.optString("error_description", err);
    }

    private static void save(Context c, JSONObject j) {
        long exp = System.currentTimeMillis() + j.optLong("expires_in", 365L * 24 * 3600) * 1000L;
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString("access", j.optString("access_token"));
        if (j.has("refresh_token")) e.putString("refresh", j.optString("refresh_token"));
        e.putLong("expires", exp);
        e.apply();
    }

    /** Продлевает доступ, если он скоро кончится. */
    private static void refreshIfNeeded(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String refresh = p.getString("refresh", null);
        if (refresh == null) return;
        if (System.currentTimeMillis() < p.getLong("expires", 0) - 7L * 24 * 3600 * 1000) return;
        String form = "grant_type=refresh_token&refresh_token=" + Net.enc(refresh) + "&client_id=" + Net.enc(CLIENT_ID) + secretParam(c);
        Net.Resp r = Net.request(c, "POST", OAUTH + "/token", form, null);
        if (r.code == 200) save(c, new JSONObject(r.body));
    }

    private static String auth(Context c) throws Exception {
        refreshIfNeeded(c);
        String t = prefs(c).getString("access", null);
        if (t == null) throw new Exception("нет входа в Яндекс");
        return "OAuth " + t;
    }

    // ---------- Диск ----------

    /** Содержимое папки на Диске; null — папки нет. */
    static JSONArray list(Context c, String path) throws Exception {
        String url = API + "/resources?path=" + Net.enc(path) + "&limit=1000"
                + "&fields=" + Net.enc("_embedded.items.name,_embedded.items.type,_embedded.items.path,_embedded.items.size,_embedded.items.file,_embedded.items.md5");
        Net.Resp r = Net.request(c, "GET", url, null, auth(c));
        if (r.code == 404) return null;
        if (r.code == 401) throw new Exception("Яндекс не пускает — войдите заново");
        if (r.code != 200) throw new Exception("Диск ответил " + r.code + " на " + path);
        JSONObject e = new JSONObject(r.body).optJSONObject("_embedded");
        return e == null ? new JSONArray() : e.optJSONArray("items");
    }

    /** Ссылка для скачивания файла, если её не было в списке. */
    static String downloadUrl(Context c, String path) throws Exception {
        Net.Resp r = Net.request(c, "GET", API + "/resources/download?path=" + Net.enc(path), null, auth(c));
        if (r.code != 200) throw new Exception("нет ссылки на " + path + " (" + r.code + ")");
        return new JSONObject(r.body).getString("href");
    }
}
