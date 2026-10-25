package ru.polka;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Яндекс Диск: вход по коду и «Забрать новое». */
public class YandexActivity extends Activity {
    private LinearLayout body;
    private TextView status;
    private volatile boolean alive = true, busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout p = Ui.page(this);
        p.addView(Ui.header(this, "Яндекс Диск", "папка «Полка» на Диске → полки книги"), Ui.matchWrap());
        p.addView(Ui.line(this, 2), Ui.margins(this, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)), 0, 12, 0, 12));
        body = Ui.column(this);
        p.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        status = Ui.text(this, "", 17, false, Ui.INK);
        p.addView(status, Ui.matchWrap());
        setContentView(p);
        render();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    private void say(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText(s); } });
    }

    private void render() {
        body.removeAllViews();
        if (Yandex.loggedIn(this)) {
            String when = Ui.prefs(this).getString("sync_when", null);
            String note = Ui.prefs(this).getString("sync_note", "");
            body.addView(Ui.text(this, "Вход выполнен.", 22, true, Ui.INK));
            body.addView(Ui.text(this, when == null ? "Ещё ни разу не забирали." : "Последний раз: " + when + "\n" + note, 17, false, Ui.GREY));
            body.addView(Ui.button(this, "Забрать новое с Диска", true, new View.OnClickListener() {
                @Override public void onClick(View v) { sync(); }
            }), Ui.margins(this, Ui.matchWrap(), 0, 20, 0, 10));
            body.addView(Ui.text(this, "Кладите файлы на Диск в папку «Полка»: внутри «Книги», «Схемы», «Документация». "
                    + "С книги ничего не удаляется. Wi-Fi «Полка» включит сама и потом выключит.", 15, false, Ui.GREY));
            body.addView(Ui.button(this, "Выйти из Яндекса", false, new View.OnClickListener() {
                @Override public void onClick(View v) { Yandex.logout(YandexActivity.this); render(); say(""); }
            }), Ui.margins(this, Ui.matchWrap(), 0, 20, 0, 0));
        } else if (Yandex.secret(this) == null) {
            body.addView(Ui.text(this, "Шаг 1. Секрет приложения", 22, true, Ui.INK));
            body.addView(Ui.text(this, "Яндекс просит секрет приложения «Полка» (Client secret на oauth.yandex.ru). "
                    + "Он хранится только в этой книге.\n\n"
                    + "Проще всего: подключите книгу к компьютеру и положите в память книги файл polka-secret.txt "
                    + "с секретом внутри. «Полка» сама его заберёт и удалит файл.\n\nИли введите здесь:", 16, false, Ui.INK));
            final android.widget.EditText field = new android.widget.EditText(this);
            field.setSingleLine(true);
            field.setTextSize(18);
            field.setHint("32 буквы и цифры");
            field.setTextColor(Ui.INK);
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            body.addView(field, Ui.margins(this, Ui.matchWrap(), 0, 8, 0, 8));
            body.addView(Ui.button(this, "Сохранить секрет", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (im != null) im.hideSoftInputFromWindow(field.getWindowToken(), 0);
                    if (Yandex.setSecret(YandexActivity.this, field.getText().toString())) { say("Секрет сохранён."); render(); }
                    else say("Не похоже на секрет: нужно ровно 32 знака (цифры и буквы a–f).");
                }
            }), Ui.matchWrap());
            body.addView(Ui.button(this, "Проверить файл polka-secret.txt", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Yandex.secret(YandexActivity.this) != null) { say("Секрет забрал из файла, файл удалил."); render(); }
                    else say("Файла с секретом в памяти книги нет.");
                }
            }), Ui.margins(this, Ui.matchWrap(), 0, 10, 0, 0));
        } else {
            body.addView(Ui.text(this, "Чтобы «Полка» забирала файлы с Диска, войдите в Яндекс. "
                    + "Пароль вводится на телефоне, книга его не увидит. Доступ — только чтение Диска.", 17, false, Ui.INK));
            body.addView(Ui.button(this, "Войти в Яндекс", true, new View.OnClickListener() {
                @Override public void onClick(View v) { login(); }
            }), Ui.margins(this, Ui.matchWrap(), 0, 20, 0, 0));
        }
    }

    private void login() {
        if (busy) return;
        busy = true;
        say("Получаю код…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean wifiWas = Sync.wifiOn(YandexActivity.this, new Sync.Log() { @Override public void say(String s) { YandexActivity.this.say(s); } });
                try {
                    final Yandex.Code k = Yandex.requestCode(YandexActivity.this);
                    runOnUiThread(new Runnable() { @Override public void run() { showCode(k); } });
                    say("Жду, пока вы введёте код на телефоне…");
                    while (alive && System.currentTimeMillis() < k.until) {
                        Thread.sleep(k.interval * 1000L);
                        String res = Yandex.poll(YandexActivity.this, k);
                        if (res == null) continue;
                        if (res.length() == 0) {
                            say("Готово! Вход выполнен.");
                            runOnUiThread(new Runnable() { @Override public void run() { render(); } });
                        } else {
                            say("Яндекс: " + res);
                        }
                        return;
                    }
                    say("Код устарел. Нажмите «Войти» ещё раз.");
                    runOnUiThread(new Runnable() { @Override public void run() { render(); } });
                } catch (Throwable e) {
                    say("Не вышло: " + e.getMessage());
                } finally {
                    busy = false;
                    if (!wifiWas) Sync.wifiOff(YandexActivity.this);
                }
            }
        }).start();
    }

    private void showCode(Yandex.Code k) {
        body.removeAllViews();
        body.addView(Ui.text(this, "На телефоне откройте", 19, false, Ui.INK));
        body.addView(Ui.text(this, "ya.ru/device", 30, true, Ui.INK));
        body.addView(Ui.text(this, "и введите код:", 19, false, Ui.INK));
        TextView code = Ui.text(this, k.userCode, 52, true, Ui.INK);
        code.setGravity(Gravity.CENTER);
        code.setBackground(Ui.box(this, false));
        code.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        body.addView(code, Ui.margins(this, Ui.matchWrap(), 0, 14, 0, 14));
        body.addView(Ui.text(this, "Потом нажмите «Разрешить». Код действует несколько минут.", 16, false, Ui.GREY));
    }

    private void sync() {
        if (busy) return;
        busy = true;
        say("Начинаю…");
        new Thread(new Runnable() {
            @Override public void run() {
                Sync.Result r = Sync.run(YandexActivity.this, new Sync.Log() { @Override public void say(String s) { YandexActivity.this.say(s); } });
                busy = false;
                say(r.note);
                runOnUiThread(new Runnable() { @Override public void run() { render(); } });
            }
        }).start();
    }
}
