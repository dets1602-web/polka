package ru.polka;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Папка полки: свежие сверху, листание страницами — без прокрутки, чтобы e-ink не мазал. */
public class FolderActivity extends Activity {
    private static final int PER_PAGE = 7;
    private File dir;
    private List<File> items;
    private int page = 0;
    private LinearLayout listBox;
    private TextView pageLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dir = new File(getIntent().getStringExtra("dir"));
        LinearLayout p = Ui.page(this);
        items = Files.list(dir);

        p.addView(Ui.header(this, dir.getName(), Ui.plural(items.size(), "файл", "файла", "файлов") + " · сначала новые"), Ui.matchWrap());
        p.addView(Ui.line(this, 2), Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)), 0, 12, 0, 0));

        listBox = Ui.column(this);
        p.addView(listBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = Ui.row(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(Ui.button(this, "‹ Назад", false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (page > 0) { page--; render(); } }
        }), Ui.weight(1));
        pageLabel = Ui.text(this, "", 16, false, Ui.INK);
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(pageLabel, new LinearLayout.LayoutParams(Ui.dp(this, 90), LinearLayout.LayoutParams.MATCH_PARENT));
        nav.addView(Ui.button(this, "Дальше ›", true, new View.OnClickListener() {
            @Override public void onClick(View v) { if ((page + 1) * PER_PAGE < items.size()) { page++; render(); } }
        }), Ui.weight(1));
        p.addView(nav, Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)), 0, 12, 0, 0));

        setContentView(p);
    }

    @Override
    protected void onResume() {
        super.onResume();
        items = Files.list(dir);
        render();
    }

    private void render() {
        listBox.removeAllViews();
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) page = pages - 1;
        pageLabel.setText((page + 1) + " / " + pages);

        if (items.isEmpty()) {
            TextView t = Ui.text(this, "Пусто.\n\nПоложи файлы в папку «" + dir.getName() + "» — они появятся здесь.", 18, false, Ui.GREY);
            t.setPadding(0, Ui.dp(this, 24), 0, 0);
            listBox.addView(t);
            return;
        }
        SimpleDateFormat df = new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru"));
        for (int i = page * PER_PAGE; i < Math.min(items.size(), (page + 1) * PER_PAGE); i++) {
            final File f = items.get(i);
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            row.setClickable(true);

            boolean fresh = !f.isDirectory() && Files.isNew(this, f);
            TextView mark = Ui.text(this, f.isDirectory() ? "ПАПКА" : (fresh ? "НОВОЕ" : ""), 11, true, Ui.PAPER);
            if (f.isDirectory() || fresh) {
                mark.setBackgroundColor(Ui.INK);
                mark.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
            }
            mark.setGravity(Gravity.CENTER);
            row.addView(mark, Ui.margins(this, new LinearLayout.LayoutParams(Ui.dp(this, 56), LinearLayout.LayoutParams.WRAP_CONTENT), 0, 0, 10, 0));

            LinearLayout txt = Ui.column(this);
            txt.addView(Ui.text(this, Files.title(f), 18, fresh || f.isDirectory(), Ui.INK));
            String sub = f.isDirectory()
                    ? Ui.plural(Files.countFiles(f), "файл", "файла", "файлов")
                    : Files.ext(f).toUpperCase(Locale.US) + " · " + df.format(new Date(f.lastModified()));
            txt.addView(Ui.text(this, sub, 13, false, Ui.GREY));
            row.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (f.isDirectory()) {
                        Intent in = new Intent(FolderActivity.this, FolderActivity.class);
                        in.putExtra("dir", f.getAbsolutePath());
                        startActivity(in);
                    } else {
                        Files.open(FolderActivity.this, f);
                    }
                }
            });
            listBox.addView(row, Ui.matchWrap());
            View sep = new View(this);
            sep.setBackgroundColor(Ui.INK);
            listBox.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        }
    }

    /** Боковые кнопки листания ONYX (и громкость, на которую они бывают назначены). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if ((page + 1) * PER_PAGE < items.size()) { page++; render(); }
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
