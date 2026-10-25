package ru.polka;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;

/**
 * Своя читалка PDF на движке Pdfium (как в Chrome): открывает и новые PDF,
 * которые старая читалка ONYX не понимает.
 * Два пальца — увеличить/уменьшить, один палец — двигать увеличенную страницу,
 * тап или свайп справа/слева и боковые кнопки — листать, двойной тап — страница целиком,
 * «Назад» на книге — выйти. Меню нет.
 */
public class PdfActivity extends Activity {
    private static final float MAX_ZOOM = 6f;

    private PdfiumCore core;
    private PdfDocument doc;
    private ParcelFileDescriptor fd;
    private File file;
    private int pages, page, offX, offY, fullW, fullH, pw = 595, ph = 842;
    /** 0 — страница целиком; иначе масштаб относительно «по ширине». */
    private float zoom;
    private ImageView img;
    private TextView info, num;
    private Bitmap bmp;
    private int sw, sh, tx, ty;
    private volatile boolean rendering, pending;

    // жесты
    private ScaleGestureDetector scaler;
    private float downX, downY, gScale = 1f, fx, fy;
    private boolean moved, scaling;
    private long lastTap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(getApplicationContext());
        file = new File(getIntent().getStringExtra("path"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        info = Ui.text(this, "Открываю…", 18, true, Ui.INK);
        info.setGravity(Gravity.CENTER);
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));

        num = Ui.text(this, "", 13, false, Ui.INK);
        num.setBackgroundColor(Color.WHITE);
        num.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2);
        np.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        root.addView(num, np);
        setContentView(root);

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                gScale = 1f;
                fx = d.getFocusX();
                fy = d.getFocusY();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector d) {
                gScale *= d.getScaleFactor();
                gScale = Math.max(0.2f, Math.min(8f, gScale));
                Matrix m = new Matrix();
                m.setTranslate(tx, ty);
                m.postScale(gScale, gScale, fx, fy);
                img.setImageMatrix(m);
                return true;
            }
        });

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return touch(v, e); }
        });

        if (!file.exists() || file.length() == 0) {
            info.setText("Файл пустой (0 байт).\n\nЗаберите его с Диска заново.");
            return;
        }
        page = Ui.prefs(this).getInt("page:" + file.getAbsolutePath(), 0);
        zoom = Ui.prefs(this).getFloat("pdfzoom", 0f);
        root.post(new Runnable() {
            @Override public void run() {
                sw = img.getWidth();
                sh = img.getHeight();
                open();
            }
        });
    }

    private boolean touch(View v, MotionEvent e) {
        scaler.onTouchEvent(e);
        int slop = Ui.dp(this, 14);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moved = false;
                scaling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaling || e.getPointerCount() > 1) break;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) + Math.abs(dy) > slop) moved = true;
                if (moved && bigger()) {
                    Matrix m = new Matrix();
                    m.setTranslate(tx + dx, ty + dy);
                    img.setImageMatrix(m);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (scaling) { applyZoom(); break; }
                float ux = e.getX() - downX, uy = e.getY() - downY;
                if (moved) {
                    if (bigger()) pan(ux, uy);
                    else if (Math.abs(ux) > Ui.dp(this, 50) && Math.abs(ux) > Math.abs(uy)) { if (ux < 0) next(); else prev(); }
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - lastTap < 350) { lastTap = 0; fitPage(); break; }
                lastTap = now;
                float x = e.getX() / v.getWidth();
                if (x > 0.66f) next();
                else if (x < 0.33f) prev();
                break;
        }
        return true;
    }

    /** Страница больше экрана — её можно двигать. */
    private boolean bigger() { return fullW > sw || fullH > sh; }

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
                        if (error != null) { info.setText(error); return; }
                        render();
                    }
                });
            }
        }).start();
    }

    private float fitWidth() { return sw / (float) pw; }

    private float fitPageScale() { return Math.min(sw / (float) pw, sh / (float) ph); }

    private float scale() { return zoom <= 0 ? fitPageScale() : fitWidth() * zoom; }

    /** Размер страницы на экране и проверка, что окно не вылезает за страницу. */
    private void layoutPage() {
        pw = core.getPageWidthPoint(doc, page);
        ph = core.getPageHeightPoint(doc, page);
        if (pw <= 0 || ph <= 0) { pw = 595; ph = 842; }
        float s = scale();
        fullW = Math.max(1, Math.round(pw * s));
        fullH = Math.max(1, Math.round(ph * s));
        offX = Math.max(0, Math.min(offX, fullW - sw));
        offY = Math.max(0, Math.min(offY, fullH - sh));
        if (fullW <= sw) offX = 0;
        if (fullH <= sh) offY = 0;
    }

    private void render() {
        if (doc == null) return;
        if (rendering) { pending = true; return; }
        rendering = true;
        pending = false;
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
                    final int bx = Math.max(0, (sw - bw) / 2), by = Math.max(0, (sh - bh) / 2);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            tx = bx;
                            ty = by;
                            Matrix m = new Matrix();
                            m.setTranslate(tx, ty);
                            img.setImageMatrix(m);
                            img.setImageBitmap(bmp);
                            info.setText("");
                            num.setText((page + 1) + " / " + pages);
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

    /** После жеста двумя пальцами: новый масштаб, точка под пальцами остаётся на месте. */
    private void applyZoom() {
        scaling = false;
        if (doc == null) return;
        float s0 = scale();
        float s1 = s0 * gScale;
        s1 = Math.max(fitPageScale(), Math.min(fitWidth() * MAX_ZOOM, s1));
        // точка страницы под центром жеста
        float px = (offX + (fx - tx)) / s0, py = (offY + (fy - ty)) / s0;
        if (s1 <= fitPageScale() * 1.01f) {
            zoom = 0;
            offX = offY = 0;
        } else {
            zoom = s1 / fitWidth();
            offX = Math.round(px * s1 - fx);
            offY = Math.round(py * s1 - fy);
        }
        save();
        render();
    }

    private void pan(float dx, float dy) {
        offX -= Math.round(dx);
        offY -= Math.round(dy);
        render();
    }

    private void fitPage() {
        zoom = 0;
        offX = offY = 0;
        save();
        render();
    }

    /** Дальше: если страница увеличена — сначала её части (слева направо, сверху вниз), потом следующая. */
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
        else if (offY > 0) { offY = Math.max(0, offY - sh); offX = Math.max(0, fullW - sw); }
        else if (page > 0) { page--; offX = offY = 0; }
        else return;
        save();
        render();
    }

    private void save() {
        Ui.prefs(this).edit().putInt("page:" + file.getAbsolutePath(), page).putFloat("pdfzoom", zoom).apply();
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
