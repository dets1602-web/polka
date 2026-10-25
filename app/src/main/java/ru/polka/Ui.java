package ru.polka;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** Общие кусочки интерфейса под e-ink: только чёрное и белое, крупно, без анимаций. */
final class Ui {
    static final int INK = Color.BLACK;
    static final int PAPER = Color.WHITE;
    static final int GREY = 0xFF444444;

    private Ui() {}

    /** Корневая папка «Полки» в памяти книги. */
    static File root() {
        File r = new File(Environment.getExternalStorageDirectory(), "Полка");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    static File folder(String name) {
        File f = new File(root(), name);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("polka", Context.MODE_PRIVATE);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable box(Context c, boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(filled ? INK : PAPER);
        g.setStroke(dp(c, 2), INK);
        return g;
    }

    /** Большая кнопка в рамке. filled = чёрная с белым текстом. */
    static TextView button(Context c, String s, boolean filled, View.OnClickListener l) {
        TextView b = text(c, s, 18, true, filled ? PAPER : INK);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(c, filled));
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        b.setOnClickListener(l);
        b.setClickable(true);
        return b;
    }

    static View line(Context c, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(INK);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams margins(Context c, LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** Экран с полями и белым фоном. */
    static LinearLayout page(Activity a) {
        LinearLayout p = column(a);
        p.setBackgroundColor(PAPER);
        int pad = dp(a, 16);
        p.setPadding(pad, pad, pad, pad);
        return p;
    }

    /** Шапка «‹ Заголовок / подпись» для внутренних экранов. */
    static LinearLayout header(final Activity a, String title, String sub) {
        LinearLayout h = row(a);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button(a, "‹", false, new View.OnClickListener() {
            @Override public void onClick(View v) { a.finish(); }
        });
        back.setTextSize(26);
        back.setContentDescription("Назад");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(a, 52), dp(a, 52));
        bp.setMargins(0, 0, dp(a, 12), 0);
        h.addView(back, bp);
        LinearLayout t = column(a);
        t.addView(text(a, title, 26, true, INK));
        if (sub != null) t.addView(text(a, sub, 14, false, GREY));
        h.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return h;
    }

    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " " + one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " " + few;
        return n + " " + many;
    }
}
