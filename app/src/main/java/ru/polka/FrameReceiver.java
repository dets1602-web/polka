package ru.polka;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Раз в час (и после включения книги): забрать новое с Диска, взять новое фото и погоду, перерисовать рамку.
 * Экран не трогаем — новая картинка покажется, когда книга уснёт.
 */
public class FrameReceiver extends BroadcastReceiver {
    static final String ACTION = "ru.polka.FRAME_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        if (!Frame.enabled(c)) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Frame.schedule(c);
            Frame.draw(c);
            return;
        }
        final PendingResult done = goAsync();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        // Держим процессор, пока качаем и рисуем (не дольше 3 минут); экран при этом не включается
        final PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "polka:frame");
        if (wl != null) wl.acquire(3 * 60 * 1000L);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Frame.refresh(c, true);
                } catch (Throwable e) {
                    Crash.note(c, "frame tick", e);
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                    done.finish();
                }
            }
        }).start();
    }
}
