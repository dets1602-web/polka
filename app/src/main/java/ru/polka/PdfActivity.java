package ru.polka;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;

/**
 * Своя читалка PDF на движке Pdfium (как в Chrome): открывает и новые PDF,
 * которые старая читалка ONYX не понимает. Постранично, без прокрутки — для e-ink.
 * Справа/кнопка «вперёд» — дальше, слева — назад, по центру — меню.
 */
public class PdfActivity extends Activity {
    /** Режимы: страница целиком, по ширине, по ширине ×2 (для чертежей). */
    private static final String[] MODES = {"Целиком", "По ширине", "Крупно ×2"};

    private PdfiumCore core;
    private PdfDocument doc;
    private ParcelFileDescriptor fd;
    private File file;
    private int pages, page, mode, offX, offY, fullW, fullH;
    private ImageView img;
    private TextView info;
    private LinearLayout bar;
    private TextView modeBtn;
    private Bitmap bmp;
    private int sw, sh;
    private volatile boolean rendering, pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(getApplicationContext());
        file = new File(getIntent().getStringExtra("path"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        info = Ui.text(this, "Открываю…", 18, true, Ui.INK);
        info.setGravity(Gravity.CENTER);
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));

        bar = Ui.column(this);
        bar.setBackgroundColor(Color.WHITE);
        int pad = Ui.dp(this, 10);
        bar.setPadding(pad, pad, pad, pad);
        LinearLayout r1 = Ui.row(this);
        r1.addView(Ui.button(this, "‹ Закрыть", false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), Ui.margins(this, Ui.weight(1), 0, 0, 6, 0));
        modeBtn = Ui.button(this, MODES[0], false, new View.OnClickListener() {
            @Override public void onClick(View v) { mode = (mode + 1) % MODES.length; offX = offY = 0; save(); render(); }
        });
        r1.addView(modeBtn, Ui.margins(this, Ui.weight(1), 6, 0, 0, 0));
        bar.addView(r1, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        LinearLayout r2 = Ui.row(this);
        r2.addView(Ui.button(this, "−10", false, new View.OnClickListener() {
            @Override public void onClick(View v) { go(page - 10); }
        }), Ui.margins(this, Ui.weight(1), 0, 8, 6, 0));
        r2.addView(Ui.button(this, "В начало", false, new View.OnClickListener() {
            @Override public void onClick(View v) { go(0); }
        }), Ui.margins(this, Ui.weight(1), 6, 8, 6, 0));
        r2.addView(Ui.button(this, "+10", false, new View.OnClickListener() {
            @Override public void onClick(View v) { go(page + 10); }
        }), Ui.margins(this, Ui.weight(1), 6, 8, 0, 0));
        bar.addView(r2, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60)));
        bar.addView(Ui.button(this, "Открыть в читалке ONYX", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openExternal(); }
        }), Ui.margins(this, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)), 0, 8, 0, 0));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2);
        bp.gravity = Gravity.BOTTOM;
        bar.setVisibility(View.GONE);
        root.addView(bar, bp);

        setContentView(root);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return true;
                if (bar.getVisibility() == View.VISIBLE) { bar.setVisibility(View.GONE); return true; }
                float x = e.getX() / v.getWidth();
                if (x > 0.66f) next();
                else if (x < 0.33f) prev();
                else bar.setVisibility(View.VISIBLE);
                return true;
            }
        });

        if (!file.exists() || file.length() == 0) {
            info.setText("Файл пустой (0 байт).\n\nЗаберите его с Диска заново.");
            return;
        }
        page = Ui.prefs(this).getInt("page:" + file.getAbsolutePath(), 0);
        mode = Ui.prefs(this).getInt("pdfmode", 0);
        root.post(new Runnable() {
            @Override public void run() {
                sw = img.getWidth();
                sh = img.getHeight();
                open();
            }
        });
    }

    private void open() {
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    core = new PdfiumCore(PdfActivity.this);
                    fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    doc = core.newDocument(fd);
                    pages = core.getPageCount(doc);
                    if (page >= pages) page = 0;
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName().contains("Password") ? "PDF закрыт паролем." : "Не открылся: " + e.getMessage();
                    Crash.note(PdfActivity.this, "pdf open " + file.getName(), e);
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (error != null) { info.setText(error + "\n\nМожно попробовать «Открыть в читалке ONYX» — тап по центру."); return; }
                        render();
                    }
                });
            }
        }).start();
    }

    /** Размер страницы целиком на экране в текущем режиме. */
    private void layoutPage() {
        int pw = core.getPageWidthPoint(doc, page), ph = core.getPageHeightPoint(doc, page);
        if (pw <= 0 || ph <= 0) { pw = 595; ph = 842; }
        float s;
        if (mode == 0) s = Math.min(sw / (float) pw, sh / (float) ph);
        else s = sw * (mode == 2 ? 2f : 1f) / pw;
        fullW = Math.max(1, Math.round(pw * s));
        fullH = Math.max(1, Math.round(ph * s));
    }

    private void render() {
        if (doc == null) return;
        if (rendering) { pending = true; return; }
        rendering = true;
        pending = false;
        modeBtn.setText(MODES[mode]);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    core.openPage(doc, page);
                    layoutPage();
                    int bw = Math.min(sw, fullW), bh = Math.min(sh, fullH);
                    Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
                    out.eraseColor(Color.WHITE);
                    core.renderPageBitmap(doc, out, page, -offX, -offY, fullW, fullH, true);
                    final Bitmap old = bmp;
                    bmp = out;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            img.setImageBitmap(bmp);
                            String part = (fullW > sw || fullH > sh) ? "  · часть " + (offY / Math.max(1, sh) * cols() + offX / Math.max(1, sw) + 1) + "/" + (rows() * cols()) : "";
                            info.setText("");
                            setTitleBar((page + 1) + " / " + pages + part);
                            if (old != null && old != bmp) old.recycle();
                        }
                    });
                } catch (Throwable e) {
                    err = "Страница " + (page + 1) + " не нарисовалась: " + e.getMessage();
                    Crash.note(PdfActivity.this, "pdf render", e);
                } finally {
                    rendering = false;
                }
                if (pending) runOnUiThread(new Runnable() { @Override public void run() { render(); } });
                if (err != null) {
                    final String m = err;
                    runOnUiThread(new Runnable() { @Override public void run() { info.setText(m); } });
                }
            }
        }).start();
    }

    private TextView pageLabel;

    private void setTitleBar(String s) {
        if (pageLabel == null) {
            pageLabel = Ui.text(this, "", 16, true, Ui.INK);
            pageLabel.setGravity(Gravity.CENTER);
            pageLabel.setPadding(0, Ui.dp(this, 6), 0, 0);
            bar.addView(pageLabel, 0);
        }
        pageLabel.setText(s);
    }

    private int cols() { return Math.max(1, (fullW + sw - 1) / Math.max(1, sw)); }

    private int rows() { return Math.max(1, (fullH + sh - 1) / Math.max(1, sh)); }

    /** Дальше: сначала по частям страницы (слева направо, сверху вниз), потом следующая страница. */
    private void next() {
        if (doc == null) return;
        if (offX + sw < fullW) offX += sw;
        else if (offY + sh < fullH) { offX = 0; offY += sh; }
        else if (page + 1 < pages) { page++; offX = offY = 0; }
        else return;
        save();
        render();
    }

    private void prev() {
        if (doc == null) return;
        if (offX > 0) offX = Math.max(0, offX - sw);
        else if (offY > 0) { offY = Math.max(0, offY - sh); offX = Math.max(0, (cols() - 1) * sw); }
        else if (page > 0) { page--; offX = offY = 0; }
        else return;
        save();
        render();
    }

    private void go(int p) {
        if (doc == null) return;
        page = Math.max(0, Math.min(pages - 1, p));
        offX = offY = 0;
        save();
        render();
    }

    private void save() {
        Ui.prefs(this).edit().putInt("page:" + file.getAbsolutePath(), page).putInt("pdfmode", mode).apply();
    }

    private void openExternal() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(file), "application/pdf");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { info.setText("Читалки ONYX для PDF нет."); }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                next();
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                prev();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        try { if (core != null && doc != null) core.closeDocument(doc); } catch (Throwable ignored) {}
        try { if (fd != null) fd.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
