package ru.polka;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Все приложения книги + выход в настройки ONYX и на старый рабочий стол. */
public class AppsActivity extends Activity {
    private static final int COLS = 3, ROWS = 3;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private int page = 0;
    private LinearLayout grid;
    private TextView pageLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout p = Ui.page(this);
        p.addView(Ui.header(this, "Приложения", "всё, что установлено на книге"), Ui.matchWrap());
        p.addView(Ui.line(this, 2), Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)), 0, 12, 0, 12));

        grid = Ui.column(this);
        p.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = Ui.row(this);
        nav.addView(Ui.button(this, "‹", false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (page > 0) { page--; render(); } }
        }), Ui.weight(1));
        pageLabel = Ui.text(this, "", 16, false, Ui.INK);
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(pageLabel, new LinearLayout.LayoutParams(Ui.dp(this, 90), LinearLayout.LayoutParams.MATCH_PARENT));
        nav.addView(Ui.button(this, "›", false, new View.OnClickListener() {
            @Override public void onClick(View v) { if ((page + 1) * COLS * ROWS < apps.size()) { page++; render(); } }
        }), Ui.weight(1));
        p.addView(nav, Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)), 0, 10, 0, 10));

        p.addView(Ui.button(this, "Настройки книги (ONYX)", true, new View.OnClickListener() {
            @Override public void onClick(View v) { openSystemSettings(AppsActivity.this); }
        }), Ui.margins(this, Ui.matchWrap(), 0, 0, 0, 10));
        p.addView(Ui.button(this, "Старый рабочий стол ONYX", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openOldLauncher(); }
        }), Ui.matchWrap());
        p.addView(Ui.button(this, "Разведчик", false, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(AppsActivity.this, ScoutActivity.class)); }
        }), Ui.margins(this, Ui.matchWrap(), 0, 10, 0, 0));
        update = Ui.button(this, "Обновить «Полку»", false, new View.OnClickListener() {
            @Override public void onClick(View v) { installUpdate(); }
        });
        p.addView(update, Ui.margins(this, Ui.matchWrap(), 0, 10, 0, 0));

        setContentView(p);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        render();
        updateLabel();
    }

    private TextView update;

    /** Новая «Полка» кладётся с компьютера сюда. */
    private static java.io.File updateFile() {
        return new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download/Polka.apk");
    }

    private int installedCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private String installedName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private void updateLabel() {
        java.io.File f = updateFile();
        android.content.pm.PackageInfo pi = f.exists() ? getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0) : null;
        if (pi == null) {
            update.setText("Обновить «Полку» · сейчас " + installedName() + ", файла нет");
        } else if (pi.versionCode > installedCode()) {
            update.setText("Обновить «Полку» до " + pi.versionName);
        } else {
            update.setText("«Полка» " + installedName() + " — новее нет");
        }
    }

    private boolean busy;

    /** Сначала качаем свежую «Полку» с GitHub по Wi-Fi; не вышло — ставим то, что лежит в Download. */
    private void installUpdate() {
        if (busy) return;
        busy = true;
        update.setText("Скачиваю «Полку» по Wi-Fi…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Net.download(AppsActivity.this, Net.UPDATE_URL, updateFile());
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        updateLabel();
                        afterDownload(error);
                    }
                });
            }
        }).start();
    }

    private void afterDownload(String error) {
        java.io.File f = updateFile();
        android.content.pm.PackageInfo pi = f.exists() ? getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0) : null;
        boolean newer = pi != null && pi.versionCode > installedCode();
        if (error != null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("По Wi-Fi не скачалось")
                    .setMessage(error + (newer ? "\n\nВ Download есть «Полка» " + pi.versionName + " — ставлю её." : "\n\nСфотографируй и пришли."))
                    .setPositiveButton("OK", null)
                    .show();
            if (!newer) return;
        } else if (!newer) {
            Toast.makeText(this, "Скачал. Новее нет — у тебя " + installedName(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(android.net.Uri.fromFile(f), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Установщик не открылся", Toast.LENGTH_LONG).show(); }
    }

    private void load() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        final PackageManager pm = getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            apps.add(r);
        }
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
    }

    private void render() {
        grid.removeAllViews();
        int per = COLS * ROWS;
        int pages = Math.max(1, (apps.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        pageLabel.setText((page + 1) + " / " + pages);
        PackageManager pm = getPackageManager();
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0);
        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = Ui.row(this);
            for (int c = 0; c < COLS; c++) {
                int idx = page * per + r * COLS + c;
                LinearLayout cell = Ui.column(this);
                cell.setGravity(Gravity.CENTER);
                if (idx < apps.size()) {
                    final ResolveInfo ri = apps.get(idx);
                    cell.setBackground(Ui.box(this, false));
                    cell.setClickable(true);
                    ImageView icon = new ImageView(this);
                    Drawable d = ri.loadIcon(pm);
                    d.setColorFilter(new ColorMatrixColorFilter(gray));
                    icon.setImageDrawable(d);
                    cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
                    TextView label = Ui.text(this, ri.loadLabel(pm).toString(), 14, true, Ui.INK);
                    label.setGravity(Gravity.CENTER);
                    label.setSingleLine(true);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    label.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
                    cell.addView(label);
                    cell.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { launch(ri); }
                    });
                }
                row.addView(cell, Ui.margins(this, Ui.weight(1), 4, 4, 4, 4));
            }
            grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }
    }

    private void launch(ResolveInfo ri) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Не запускается", Toast.LENGTH_SHORT).show(); }
    }

    /** Открывает рабочий стол ONYX (любой «домашний» экран, кроме нашего). */
    private void openOldLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : getPackageManager().queryIntentActivities(home, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return; } catch (Exception ignored) {}
        }
        Toast.makeText(this, "Старый рабочий стол не найден", Toast.LENGTH_LONG).show();
    }

    static void openSystemSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(c, "Настройки не открылись", Toast.LENGTH_LONG).show();
        }
    }

    /** Боковые кнопки листания ONYX (и громкость, на которую они бывают назначены). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if ((page + 1) * COLS * ROWS < apps.size()) { page++; render(); }
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (page > 0) { page--; render(); }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
