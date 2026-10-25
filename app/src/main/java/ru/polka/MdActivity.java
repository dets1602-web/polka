package ru.polka;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Читалка Markdown (.md) и простого текста (.txt) для e-ink:
 * заголовки, жирный, списки, цитаты, код и таблицы — постранично, без прокрутки.
 */
public class MdActivity extends Activity {
    private static final int[] SIZES = {17, 20, 23, 27};

    private File file;
    private CharSequence text;
    private PageView view;
    private LinearLayout bar;
    private TextView label;
    private int size;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(getApplicationContext());
        file = new File(getIntent().getStringExtra("path"));
        size = Ui.prefs(this).getInt("mdsize", 1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        view = new PageView(this);
        int pad = Ui.dp(this, 16);
        view.setPadding(pad, pad, pad, pad);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        bar = Ui.column(this);
        bar.setBackgroundColor(Color.WHITE);
        int bp = Ui.dp(this, 10);
        bar.setPadding(bp, bp, bp, bp);
        label = Ui.text(this, "", 16, true, Ui.INK);
        label.setGravity(Gravity.CENTER);
        bar.addView(label);
        LinearLayout r1 = Ui.row(this);
        r1.addView(Ui.button(this, "‹ Закрыть", false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), Ui.margins(this, Ui.weight(1), 0, 6, 6, 0));
        r1.addView(Ui.button(this, "В начало", false, new View.OnClickListener() {
            @Override public void onClick(View v) { view.go(0); hideBar(); }
        }), Ui.margins(this, Ui.weight(1), 6, 6, 0, 0));
        bar.addView(r1, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));
        LinearLayout r2 = Ui.row(this);
        r2.addView(Ui.button(this, "Шрифт −", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setSize(size - 1); }
        }), Ui.margins(this, Ui.weight(1), 0, 8, 6, 0));
        r2.addView(Ui.button(this, "Шрифт +", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setSize(size + 1); }
        }), Ui.margins(this, Ui.weight(1), 6, 8, 0, 0));
        bar.addView(r2, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        blp.gravity = Gravity.BOTTOM;
        bar.setVisibility(View.GONE);
        root.addView(bar, blp);
        setContentView(root);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return true;
                if (bar.getVisibility() == View.VISIBLE) { hideBar(); return true; }
                float x = e.getX() / v.getWidth();
                if (x > 0.66f) view.next();
                else if (x < 0.33f) view.prev();
                else { updateLabel(); bar.setVisibility(View.VISIBLE); }
                return true;
            }
        });

        try {
            String raw = read(file);
            boolean md = !Files.ext(file).equals("txt");
            text = md ? Md.render(raw) : raw;
        } catch (Throwable e) {
            Crash.note(this, "md read", e);
            text = "Не удалось прочитать файл: " + e.getMessage();
        }
        view.setText(text, Ui.prefs(this).getInt("mdpos:" + file.getAbsolutePath(), 0));
    }

    private void hideBar() { bar.setVisibility(View.GONE); }

    private void updateLabel() { label.setText(Files.title(file) + " · " + view.label()); }

    private void setSize(int s) {
        size = Math.max(0, Math.min(SIZES.length - 1, s));
        Ui.prefs(this).edit().putInt("mdsize", size).apply();
        view.relayout();
        updateLabel();
    }

    private void savePos(int charPos) {
        Ui.prefs(this).edit().putInt("mdpos:" + file.getAbsolutePath(), charPos).apply();
    }

    private static String read(File f) throws Exception {
        if (f.length() > 4L * 1024 * 1024) throw new Exception("файл больше 4 МБ");
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = r.readLine()) != null) sb.append(ln).append('\n');
        r.close();
        if (sb.length() > 0 && sb.charAt(0) == '﻿') sb.deleteCharAt(0);
        return sb.toString();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                view.next();
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                view.prev();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Рисует текст постранично: только целые строки, без прокрутки. */
    private final class PageView extends View {
        private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        private CharSequence src = "";
        private StaticLayout layout;
        private final List<Integer> starts = new ArrayList<>();
        private int page, wantChar;

        PageView(Context c) {
            super(c);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.SERIF);
        }

        void setText(CharSequence t, int charPos) {
            src = t;
            wantChar = charPos;
            relayout();
        }

        void relayout() {
            if (layout != null && !starts.isEmpty()) wantChar = layout.getLineStart(starts.get(page));
            layout = null;
            requestLayout();
            invalidate();
        }

        private void build() {
            int w = getWidth() - getPaddingLeft() - getPaddingRight();
            int h = getHeight() - getPaddingTop() - getPaddingBottom();
            if (w <= 0 || h <= 0) return;
            paint.setTextSize(SIZES[size] * getResources().getDisplayMetrics().scaledDensity);
            layout = new StaticLayout(src, paint, w, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
            starts.clear();
            int line = 0, n = layout.getLineCount();
            while (line < n) {
                starts.add(line);
                int top = layout.getLineTop(line);
                int next = line + 1;
                while (next < n && layout.getLineBottom(next) - top <= h) next++;
                line = Math.max(next, line + 1);
            }
            if (starts.isEmpty()) starts.add(0);
            page = 0;
            for (int i = 0; i < starts.size(); i++) if (layout.getLineStart(starts.get(i)) <= wantChar) page = i;
        }

        private int endLine(int p) {
            return p + 1 < starts.size() ? starts.get(p + 1) : layout.getLineCount();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (layout == null) build();
            if (layout == null) return;
            int first = starts.get(page), last = endLine(page) - 1;
            int top = layout.getLineTop(first), bottom = layout.getLineBottom(last);
            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop() - top);
            canvas.clipRect(0, top, getWidth(), bottom);
            layout.draw(canvas);
            canvas.restore();
            paint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
            String num = (page + 1) + " / " + starts.size();
            canvas.drawText(num, getWidth() - getPaddingRight() - paint.measureText(num), getHeight() - Ui.dp(getContext(), 4), paint);
            paint.setTextSize(SIZES[size] * getResources().getDisplayMetrics().scaledDensity);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            relayout();
        }

        void next() { if (layout != null && page + 1 < starts.size()) { page++; changed(); } }

        void prev() { if (layout != null && page > 0) { page--; changed(); } }

        void go(int p) { if (layout != null) { page = Math.max(0, Math.min(starts.size() - 1, p)); changed(); } }

        private void changed() {
            savePos(layout.getLineStart(starts.get(page)));
            invalidate();
        }

        String label() { return layout == null ? "" : "стр. " + (page + 1) + " из " + starts.size(); }
    }

    /** Простой разбор Markdown в оформленный текст. */
    static final class Md {
        private static final Pattern H = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
        private static final Pattern UL = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
        private static final Pattern OL = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
        private static final Pattern HR = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
        private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}.*$");
        private static final Pattern INLINE = Pattern.compile(
                "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(\\*[^*\\s][^*]*\\*)|(!?\\[[^\\]]*\\]\\([^)]*\\))");
        private static final float[] HS = {1.55f, 1.35f, 1.2f, 1.1f, 1.05f, 1.0f};

        static CharSequence render(String raw) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            String[] lines = raw.split("\n", -1);
            StringBuilder para = new StringBuilder();
            boolean code = false;
            for (String line : lines) {
                String t = line.replace("\t", "    ");
                if (t.trim().startsWith("```")) {
                    flush(out, para);
                    code = !code;
                    continue;
                }
                if (code) {
                    int s = out.length();
                    out.append(t).append('\n');
                    out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.85f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    continue;
                }
                if (t.trim().length() == 0) { flush(out, para); continue; }
                Matcher m;
                if ((m = H.matcher(t)).matches()) {
                    flush(out, para);
                    if (out.length() > 0) out.append('\n');
                    int s = out.length();
                    inline(out, m.group(2));
                    int lvl = m.group(1).length();
                    out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(HS[lvl - 1]), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append("\n");
                    continue;
                }
                if (HR.matcher(t).matches()) {
                    flush(out, para);
                    out.append("――――――――――――――――\n");
                    continue;
                }
                if (t.trim().startsWith("|")) {
                    flush(out, para);
                    if (TABLE_SEP.matcher(t.replace("|", "")).matches() && t.contains("-")) continue;
                    int s = out.length();
                    out.append(t.trim()).append('\n');
                    out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.8f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    continue;
                }
                if (t.trim().startsWith(">")) {
                    flush(out, para);
                    int s = out.length();
                    inline(out, t.trim().replaceFirst("^>+\\s?", ""));
                    out.append('\n');
                    out.setSpan(new QuoteSpan(Color.BLACK), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    continue;
                }
                if ((m = UL.matcher(t)).matches() || OL.matcher(t).matches()) {
                    flush(out, para);
                    boolean ul = UL.matcher(t).matches();
                    Matcher mm = ul ? UL.matcher(t) : OL.matcher(t);
                    mm.matches();
                    int depth = mm.group(1).length() / 2;
                    String body = ul ? mm.group(2) : mm.group(3);
                    String mark = ul ? "•  " : mm.group(2) + ".  ";
                    if (ul && body.startsWith("[ ] ")) { mark = "☐  "; body = body.substring(4); }
                    else if (ul && (body.startsWith("[x] ") || body.startsWith("[X] "))) { mark = "☑  "; body = body.substring(4); }
                    int s = out.length();
                    out.append(mark);
                    inline(out, body);
                    out.append('\n');
                    out.setSpan(new LeadingMarginSpan.Standard(28 * depth, 28 * depth + 28), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    continue;
                }
                if (para.length() > 0) para.append(' ');
                para.append(t.trim());
            }
            flush(out, para);
            return out;
        }

        private static void flush(SpannableStringBuilder out, StringBuilder para) {
            if (para.length() == 0) return;
            inline(out, para.toString());
            out.append("\n\n");
            para.setLength(0);
        }

        private static void inline(SpannableStringBuilder out, String s) {
            Matcher m = INLINE.matcher(s);
            int last = 0;
            while (m.find()) {
                out.append(s, last, m.start());
                String tok = m.group();
                int st = out.length();
                if (m.group(1) != null) {
                    out.append(tok.substring(1, tok.length() - 1));
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (m.group(2) != null || m.group(3) != null) {
                    out.append(tok.substring(2, tok.length() - 2));
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (m.group(4) != null) {
                    out.append(tok.substring(1, tok.length() - 1));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    String inner = tok.substring(tok.indexOf('[') + 1, tok.indexOf(']'));
                    out.append(tok.startsWith("!") ? "[картинка" + (inner.length() > 0 ? ": " + inner : "") + "]" : inner);
                }
                last = m.end();
            }
            out.append(s.substring(last));
        }
    }
}
