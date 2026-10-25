package ru.polka;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Работа с файлами полки: список, «новое», открыть в читалке. */
final class Files {
    private static final long NEW_MS = 3L * 24 * 60 * 60 * 1000; // «НОВОЕ» — 3 дня

    private Files() {}

    /** Сначала папки по имени, потом файлы — свежие сверху. Скрытые не показываем. */
    static List<File> list(File dir) {
        File[] arr = dir.listFiles();
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        if (arr != null) {
            for (File f : arr) {
                if (f.getName().startsWith(".")) continue;
                (f.isDirectory() ? dirs : files).add(f);
            }
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        Collections.sort(files, new Comparator<File>() {
            // Свежие сверху; что пришло в одни и те же 5 минут — по имени (страницы 01, 02, …)
            @Override public int compare(File a, File b) {
                long x = b.lastModified() / 300000, y = a.lastModified() / 300000;
                if (x != y) return x < y ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        dirs.addAll(files);
        return dirs;
    }

    static int countFiles(File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return 0;
        int n = 0;
        for (File f : arr) {
            if (f.getName().startsWith(".")) continue;
            n += f.isDirectory() ? countFiles(f) : 1;
        }
        return n;
    }

    static int countNew(Context c, File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return 0;
        int n = 0;
        for (File f : arr) {
            if (f.getName().startsWith(".")) continue;
            n += f.isDirectory() ? countNew(c, f) : (isNew(c, f) ? 1 : 0);
        }
        return n;
    }

    static boolean isNew(Context c, File f) {
        return System.currentTimeMillis() - f.lastModified() < NEW_MS && !opened(c).contains(f.getAbsolutePath());
    }

    private static Set<String> opened(Context c) {
        return new HashSet<>(Ui.prefs(c).getStringSet("opened", new HashSet<String>()));
    }

    /** Имя без расширения — для красоты. */
    static String title(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return (dot > 0 && !f.isDirectory()) ? n.substring(0, dot) : n;
    }

    static String ext(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** Открывает файл в родной читалке/просмотрщике ONYX и запоминает как «Читаю сейчас». */
    static void open(Context c, File f) {
        if (ext(f).equals("pdf")) {
            // PDF — своей читалкой на Pdfium: старая читалка ONYX новые PDF не открывает
            Intent pi = new Intent(c, PdfActivity.class);
            pi.putExtra("path", f.getAbsolutePath());
            pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(pi);
            remember(c, f);
            return;
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f));
        if (mime == null) {
            String e = ext(f);
            if (e.equals("fb2")) mime = "application/x-fictionbook+xml";
            else if (e.equals("djvu") || e.equals("djv")) mime = "image/vnd.djvu";
            else if (e.equals("epub")) mime = "application/epub+zip";
            else mime = "*/*";
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), mime);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            remember(c, f);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "Нечем открыть ." + ext(f), Toast.LENGTH_LONG).show();
        }
    }

    private static void remember(Context c, File f) {
        SharedPreferences p = Ui.prefs(c);
        Set<String> o = opened(c);
        o.add(f.getAbsolutePath());
        p.edit().putStringSet("opened", o).putString("last", f.getAbsolutePath()).apply();
    }

    static final List<String> SHELVES = Arrays.asList("Книги", "Схемы", "Документация");
}
