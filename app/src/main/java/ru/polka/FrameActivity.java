package ru.polka;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Рамка во сне: включить/выключить, посмотреть, обновить. */
public class FrameActivity extends Activity {
    private LinearLayout body;
    private ImageView preview;
    private TextView status;
    private Bitmap shown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(getApplicationContext());
        LinearLayout p = Ui.page(this);
        p.addView(Ui.header(this, "Рамка", "что книга показывает во сне"), Ui.matchWrap());
        p.addView(Ui.line(this, 2), Ui.margins(this, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)), 0, 10, 0, 10));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.box(this, false));
        int pad = Ui.dp(this, 3);
        preview.setPadding(pad, pad, pad, pad);
        p.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        status = Ui.text(this, "", 15, false, Ui.GREY);
        p.addView(status, Ui.margins(this, Ui.matchWrap(), 0, 8, 0, 4));
        body = Ui.column(this);
        p.addView(body, Ui.matchWrap());
        setContentView(p);
        render();
    }

    private void render() {
        body.removeAllViews();
        Bitmap old = shown;
        shown = Frame.picture(this);
        preview.setImageBitmap(shown);
        if (old != null) old.recycle();
        boolean on = Frame.enabled(this);
        String when = Ui.prefs(this).getString("frame_when", null);
        status.setText(on
                ? "Рамка включена" + (when != null ? " · обновлена " + when : "") + ". Раз в час «Полка» забирает новое с Диска и перерисовывает её. Новая картинка появится, когда книга уснёт (иногда — со второго раза)."
                : "Сейчас во сне — картинки ONYX. Включите, и книга будет показывать вот это.");
        LinearLayout row = Ui.row(this);
        if (on) {
            row.addView(Ui.button(this, "Обновить сейчас", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String err = Frame.draw(FrameActivity.this);
                    render();
                    if (err != null) status.setText("Не получилось: " + err);
                }
            }), Ui.margins(this, Ui.weight(1), 0, 0, 6, 0));
            row.addView(Ui.button(this, "Выключить", false, new View.OnClickListener() {
                @Override public void onClick(View v) { Frame.disable(FrameActivity.this); render(); }
            }), Ui.margins(this, Ui.weight(1), 6, 0, 0, 0));
        } else {
            row.addView(Ui.button(this, "Включить рамку во сне", true, new View.OnClickListener() {
                @Override public void onClick(View v) { Frame.enable(FrameActivity.this); render(); }
            }), Ui.weight(1));
        }
        body.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
    }

    @Override
    protected void onDestroy() {
        preview.setImageBitmap(null);
        if (shown != null) shown.recycle();
        super.onDestroy();
    }
}
