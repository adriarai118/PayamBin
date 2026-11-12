package com.adriarai.payambin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** «پیدا کردن گوشی»: زنگ با صدای کامل (کانال هشدار) + لرزش، تا ۶۰ ثانیه */
public final class Ringer {
    private Ringer() {}

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static int savedVolume = -1;
    public static volatile boolean ringing = false;
    private static final int NOTIF_ID = 77;
    private static final Runnable AUTO_STOP = () -> stopNow(null);

    public static void start(Context c) {
        final Context ctx = c.getApplicationContext();
        H.post(() -> {
            stopNow(ctx);
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (Exception ignored) {
            }
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setDataSource(ctx, u);
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception e) {
                Diag.log("پخش زنگ ممکن نشد: " + e.getMessage());
            }
            vibrate(ctx, true);
            ringing = true;
            showNotification(ctx);
            Diag.log("پیدا کردن گوشی: زنگ شروع شد");
            H.postDelayed(AUTO_STOP, 60_000);
        });
    }

    public static void stop(Context c) {
        final Context ctx = c.getApplicationContext();
        H.post(() -> stopNow(ctx));
    }

    private static void stopNow(Context ctx) {
        H.removeCallbacks(AUTO_STOP);
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Exception ignored) {
        }
        player = null;
        Context c = ctx != null ? ctx : appCtx;
        if (c != null) {
            vibrate(c, false);
            try {
                if (savedVolume >= 0) {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
                }
            } catch (Exception ignored) {
            }
            try {
                ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            } catch (Exception ignored) {
            }
        }
        savedVolume = -1;
        ringing = false;
    }

    private static Context appCtx;

    @SuppressWarnings("deprecation")
    private static void vibrate(Context c, boolean on) {
        appCtx = c;
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (!on) {
                v.cancel();
                return;
            }
            long[] pattern = {0, 800, 400};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            else v.vibrate(pattern, 0);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void showNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel("ring", "پیدا کردن گوشی", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent stop = PendingIntent.getService(c, 5,
                    new Intent(c, ServerService.class).setAction(ServerService.ACTION_STOP_RING),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "ring") : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("📳 گوشی از کامپیوتر زنگ زده شد")
                    .setContentText("لمس کنید تا قطع شود")
                    .setOngoing(true)
                    .setContentIntent(stop)
                    .addAction(new Notification.Action.Builder(null, "پیدا شد — قطع کن", stop).build());
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }
}
