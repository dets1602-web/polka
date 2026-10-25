package ru.polka;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/** Подсветка Moon Light: пишем яркость прямо в файл ядра (на Vasco da Gama 2 — /sys/class/backlight/rk28_bl). */
final class Light {
    private static final String[] DIRS = {"/sys/class/backlight", "/sys/class/leds"};
    /** Ступени по нажатию: выкл → тихо → средне → ярко → выкл. */
    static final int[] STEPS = {0, 15, 45, 100};

    private Light() {}

    private static File node() {
        for (String base : DIRS) {
            File[] list = new File(base).listFiles();
            if (list == null) continue;
            for (File d : list) if (new File(d, "brightness").canWrite()) return d;
        }
        return null;
    }

    static boolean available() { return node() != null; }

    private static int read(File f, int def) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    /** Текущая яркость в процентах, -1 если подсветки нет. */
    static int get() {
        File d = node();
        if (d == null) return -1;
        int max = read(new File(d, "max_brightness"), 255);
        int v = read(new File(d, "brightness"), 0);
        return max <= 0 ? 0 : Math.round(v * 100f / max);
    }

    static boolean set(int pct) {
        File d = node();
        if (d == null) return false;
        int max = read(new File(d, "max_brightness"), 255);
        try {
            FileWriter w = new FileWriter(new File(d, "brightness"));
            w.write(String.valueOf(pct * max / 100));
            w.close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Следующая ступень после текущей яркости. */
    static int next(int now) {
        for (int s : STEPS) if (s > now + 2) return s;
        return 0;
    }

    static String label(int pct) {
        if (pct < 0) return "Свет —";
        if (pct == 0) return "Свет: выкл";
        return "Свет: " + pct + "%";
    }
}
