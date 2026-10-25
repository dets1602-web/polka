package ru.polka;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Главный экран «Полки» — заменяет рабочий стол ONYX. */
public class HomeActivity extends Activity {

    private static final Locale RU = new Locale("ru");
    private TextView time, date, battery;
    private LinearLayout reading, tiles;
    private TextView light, disk;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(getApplicationContext());
        for (String s : Files.SHELVES) Ui.folder(s);

        LinearLayout p = Ui.page(this);

        // Шапка: время, дата, заряд
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout left = Ui.column(this);
        time = Ui.text(this, "", 60, true, Ui.INK);
        time.setIncludeFontPadding(false);
        date = Ui.text(this, "", 17, false, Ui.INK);
        left.addView(time);
        left.addView(date);
        head.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        battery = Ui.text(this, "", 16, false, Ui.INK);
        battery.setGravity(Gravity.END);
        head.addView(battery);
        p.addView(head, Ui.matchWrap());
        p.addView(Ui.line(this, 2), Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)), 0, 10, 0, 14));

        // «Читаю сейчас»
        reading = Ui.column(this);
        p.addView(reading, Ui.matchWrap());

        // Плитки 2×2
        tiles = Ui.column(this);
        p.addView(tiles, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Яндекс Диск
        disk = Ui.button(this, "Яндекс Диск", false, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, YandexActivity.class)); }
        });
        p.addView(disk, Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)), 0, 12, 0, 0));

        // Низ: приложения и настройки
        LinearLayout bottom = Ui.row(this);
        bottom.addView(Ui.button(this, "Приложения", true, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, AppsActivity.class)); }
        }), Ui.margins(this, Ui.weight(1), 0, 0, 6, 0));
        light = Ui.button(this, "Свет", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int pct = Light.next(Light.get());
                if (!Light.set(pct)) Toast.makeText(HomeActivity.this, "Подсветка не отвечает", Toast.LENGTH_SHORT).show();
                updateLight();
            }
        });
        bottom.addView(light, Ui.margins(this, Ui.weight(1), 6, 0, 6, 0));
        bottom.addView(Ui.button(this, "Настройки", false, new View.OnClickListener() {
            @Override public void onClick(View v) { AppsActivity.openSystemSettings(HomeActivity.this); }
        }), Ui.margins(this, Ui.weight(1), 6, 0, 0, 0));
        p.addView(bottom, Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)), 0, 12, 0, 0));

        setContentView(p);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateClock();
        updateReading();
        updateTiles();
        updateLight();
        updateDisk();
    }

    private void updateDisk() {
        if (!Yandex.loggedIn(this)) { disk.setText("Яндекс Диск · войти"); return; }
        String when = Ui.prefs(this).getString("sync_when", null);
        disk.setText(when == null ? "Яндекс Диск · забрать новое" : "Яндекс Диск · проверено " + when);
    }

    private void updateLight() {
        int pct = Light.get();
        light.setText(Light.label(pct));
        light.setVisibility(pct < 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tick); } catch (Exception ignored) {}
    }

    /** Это рабочий стол: «Назад» никуда не уводит. */
    @Override
    public void onBackPressed() {}

    private void updateClock() {
        Date now = new Date();
        time.setText(new SimpleDateFormat("HH:mm", RU).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", RU).format(now));
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean ch = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (lvl >= 0) battery.setText((ch ? "заряжается " : "") + (lvl * 100 / sc) + "%");
        }
    }

    private void updateReading() {
        reading.removeAllViews();
        String last = Ui.prefs(this).getString("last", null);
        if (last == null) return;
        final File f = new File(last);
        if (!f.exists()) return;
        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.box(this, false));
        int pad = Ui.dp(this, 12);
        box.setPadding(pad, pad, pad, pad);
        box.addView(Ui.text(this, "ЧИТАЮ СЕЙЧАС", 13, true, Ui.GREY));
        box.addView(Ui.text(this, Files.title(f), 19, true, Ui.INK));
        box.addView(Ui.text(this, f.getParentFile().getName() + " · нажми, чтобы продолжить", 14, false, Ui.GREY));
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Files.open(HomeActivity.this, f); }
        });
        reading.addView(box, Ui.margins(this, Ui.matchWrap(), 0, 0, 0, 12));
    }

    private void updateTiles() {
        tiles.removeAllViews();
        String[] names = {"Книги", "Схемы", "Документация", "Рамка"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(this);
            for (int col = 0; col < 2; col++) {
                row.addView(tile(names[r * 2 + col]), Ui.margins(this, Ui.weight(1), col == 0 ? 0 : 6, 0, col == 0 ? 6 : 0, 0));
            }
            tiles.addView(row, Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1), 0, r == 0 ? 0 : 6, 0, r == 0 ? 6 : 0));
        }
    }

    private View tile(final String name) {
        LinearLayout t = Ui.column(this);
        t.setBackground(Ui.box(this, false));
        int pad = Ui.dp(this, 14);
        t.setPadding(pad, pad, pad, pad);
        t.setGravity(Gravity.BOTTOM);
        t.setClickable(true);

        String sub;
        int fresh = 0;
        if (name.equals("Рамка")) {
            sub = "скоро · пока «Разведчик»";
        } else {
            File dir = Ui.folder(name);
            int n = Files.countFiles(dir);
            fresh = Files.countNew(this, dir);
            sub = Ui.plural(n, "файл", "файла", "файлов");
        }
        if (fresh > 0) {
            TextView badge = Ui.text(this, "+" + fresh + " новых", 14, true, Ui.PAPER);
            badge.setBackgroundColor(Ui.INK);
            badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.gravity = Gravity.END;
            View spacer = new View(this);
            t.addView(badge, bp);
            t.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        }
        t.addView(Ui.text(this, name, 22, true, Ui.INK));
        t.addView(Ui.text(this, sub, 14, false, Ui.GREY));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (name.equals("Рамка")) {
                    startActivity(new Intent(HomeActivity.this, ScoutActivity.class));
                } else {
                    Intent i = new Intent(HomeActivity.this, FolderActivity.class);
                    i.putExtra("dir", Ui.folder(name).getAbsolutePath());
                    startActivity(i);
                }
            }
        });
        return t;
    }
}
