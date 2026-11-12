package com.adriarai.payambin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;

/** سرویس پیش‌زمینه که وب‌سرور را روشن نگه می‌دارد */
public class ServerService extends Service {
    public static final String ACTION_STOP = "com.adriarai.payambin.STOP";
    private static final String CHANNEL = "server";
    private static final int NOTIF_ID = 1;

    public static volatile boolean running = false;
    public static volatile String lastError = null;

    private WebServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock, wifiLock2;
    private ContentObserver smsObserver, callObserver;
    private WifiManager.MulticastLock mcLock;
    private Mdns mdns;
    private boolean phoneReceiverOn = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            // اگر IP عوض شد، اعلان به‌روز شود
            if (running) {
                updateNotification();
                Updater.maybeCheck(ServerService.this);
                handler.postDelayed(this, 30_000);
            }
        }
    };

    public static void start(Context c) {
        Prefs.setWantRunning(c, true);
        Intent i = new Intent(c, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        Prefs.setWantRunning(c, false);
        c.stopService(new Intent(c, ServerService.class));
    }

    public static String address(Context c) {
        List<String> ips = Net.localIps();
        if (ips.isEmpty()) return null;
        return "http://" + ips.get(0) + ":" + Prefs.port(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "سرور پیام‌بین", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setWantRunning(this, false);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            // مثلاً اجازهٔ شروع از پس‌زمینه داده نشد
            lastError = "شروع سرویس ممکن نشد: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (server == null) {
            server = new WebServer(this, Prefs.port(this));
            try {
                server.start();
                running = true;
                lastError = null;
            } catch (Exception e) {
                server = null;
                running = false;
                lastError = "پورت " + Prefs.port(this) + " قابل استفاده نیست: " + e.getMessage();
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks();
            registerSmsObserver();
            registerPhone();
            startMdns();
            handler.postDelayed(refresher, 20_000);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PayamBin::server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            // HIGH_PERF با صفحهٔ خاموش هم وای‌فای را از حالت کم‌مصرف بیرون نگه می‌دارد
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PayamBin::wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            if (Build.VERSION.SDK_INT >= 29) {
                // LOW_LATENCY فقط وقتی صفحه روشن است اثر دارد؛ برای سرعت بیشتر در آن حالت
                wifiLock2 = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PayamBin::wifi-ll");
                wifiLock2.setReferenceCounted(false);
                wifiLock2.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void registerSmsObserver() {
        try {
            smsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    NotifStore.Versions.sms.incrementAndGet();
                }
            };
            getContentResolver().registerContentObserver(Uri.parse("content://sms"), true, smsObserver);
        } catch (Exception ignored) {
        }
    }

    private void startMdns() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            mcLock = wm.createMulticastLock("PayamBin::mdns");
            mcLock.setReferenceCounted(false);
            mcLock.acquire();
        } catch (Exception ignored) {
        }
        mdns = new Mdns();
        mdns.start();
    }

    private void registerPhone() {
        try {
            IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(PhoneState.RECEIVER, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(PhoneState.RECEIVER, f);
            phoneReceiverOn = true;
        } catch (Exception ignored) {
        }
        try {
            callObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    NotifStore.Versions.calls.incrementAndGet();
                }
            };
            getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        String addr = address(this);
        String text = addr == null ? "به وای‌فای وصل نیستید"
                : "http://" + Mdns.HOST + ":" + Prefs.port(this) + "   رمز: " + Prefs.pin(this);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ServerService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : legacyBuilder();
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("پیام‌بین روشن است")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "توقف", stop).build());
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder legacyBuilder() {
        return new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        server = null;
        try {
            if (smsObserver != null) getContentResolver().unregisterContentObserver(smsObserver);
            if (callObserver != null) getContentResolver().unregisterContentObserver(callObserver);
        } catch (Exception ignored) {
        }
        try {
            if (phoneReceiverOn) unregisterReceiver(PhoneState.RECEIVER);
        } catch (Exception ignored) {
        }
        phoneReceiverOn = false;
        if (mdns != null) mdns.stop();
        mdns = null;
        try {
            if (mcLock != null && mcLock.isHeld()) mcLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wifiLock2 != null && wifiLock2.isHeld()) wifiLock2.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
