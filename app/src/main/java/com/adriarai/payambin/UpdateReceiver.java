package com.adriarai.payambin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** نتیجهٔ نصب آپدیت از PackageInstaller */
public class UpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "update";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.status = "آپدیت آماده است؛ منتظر تأیید نصب";
            try {
                c.startActivity(confirm);
            } catch (Exception ignored) {
            }
            notifyConfirm(c, confirm);
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            Updater.status = "آپدیت نصب شد";
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.status = "نصب آپدیت انجام نشد" + (msg != null ? ": " + msg : "");
        }
    }

    @SuppressWarnings("deprecation")
    private void notifyConfirm(Context c, Intent confirm) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "آپدیت برنامه", NotificationManager.IMPORTANCE_HIGH));
            }
            int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 2, confirm, f);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("نسخهٔ جدید پیام‌بین آماده است")
                    .setContentText("برای نصب لمس کنید")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(2, b.build());
        } catch (Exception ignored) {
        }
    }
}
