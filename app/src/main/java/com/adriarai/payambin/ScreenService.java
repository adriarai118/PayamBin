package com.adriarai.payambin;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/** دیدن صفحه‌ی گوشی روی کامپیوتر (MediaProjection → MJPEG) */
public class ScreenService extends Service {
    public static final String ACTION_START = "com.adriarai.payambin.SCREEN_START";
    public static final String ACTION_STOP = "com.adriarai.payambin.SCREEN_STOP";
    private static final int NOTIF_ID = 90, ASK_ID = 91;
    private static final String CH = "screen";

    public static volatile String state = "off"; // off | waiting_tap | on | error
    public static volatile String error = null;
    public static volatile String quality = "mid";
    public static volatile boolean dimPref = true;
    public static volatile int fps = 15;
    public static volatile int realW = 0, realH = 0, capW = 0, capH = 0;
    public static final AtomicInteger viewers = new AtomicInteger();
    public static final Object LOCK = new Object();
    public static volatile byte[] frame;
    public static volatile long seq = 0;
    private static ScreenService instance;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastFrame = 0;
    private int dpi = 320;
    private PowerManager.WakeLock screenLock;
    private boolean paused = false;
    private Bitmap lastBitmap;

    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            checkSize();
            // نگه داشتن صفحه‌ی روشن فقط وقتی کسی تماشا می‌کند
            boolean watching = viewers.get() > 0;
            if (watching && (screenLock == null || !screenLock.isHeld())) acquireScreen(false);
            if (!watching && screenLock != null && screenLock.isHeld()) screenLock.release();
            if (watching && Prefs.screenDim(ScreenService.this)) dimOn();
            else dimOff();
            setPaused(!watching);
            main.postDelayed(this, 1000);
        }
    };

    // ---------------- درخواست از کامپیوتر ----------------

    public static void request(Context c) {
        Context app = c.getApplicationContext();
        error = null;
        if (instance != null && instance.projection != null) {
            state = "on";
            return;
        }
        // اندروید هر بار رضایت کاربر را برای ضبط صفحه می‌خواهد
        if (MainActivity.visible) {
            try {
                app.startActivity(new Intent(app, ScreenCaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                state = "waiting_tap";
                return;
            } catch (Exception ignored) {
            }
        }
        askTap(app);
    }

    public static void stop(Context c) {
        c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), ScreenService.class));
        state = "off";
    }

    public static JSONObject status() {
        return J.obj("state", state, "error", error, "quality", quality, "fps", fps, "dim", dimPref,
                "w", capW, "h", capH, "realW", realW, "realH", realH,
                "control", ControlService.instance != null, "viewers", viewers.get());
    }

    /** روشن کردن صفحه‌ی گوشی */
    public static void wake(Context c) {
        ScreenService s = instance;
        if (s != null) s.acquireScreen(true);
        else {
            try {
                PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PayamBin::wake");
                wl.acquire(10_000);
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    static void askTap(Context c) {
        state = "waiting_tap";
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel(CH + "_ask", "درخواست دیدن صفحه", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 11,
                    new Intent(c, ScreenCaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH + "_ask") : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("🖥 کامپیوتر می‌خواهد صفحه‌ی گوشی را ببیند")
                    .setContentText("لمس کنید و در پنجره‌ی بعد «شروع» را بزنید")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(ASK_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    // ---------------- سرویس ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "اشتراک صفحه", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ASK_ID);
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            state = "error";
            error = "شروع اشتراک صفحه ممکن نشد: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        if (code != Activity.RESULT_OK || data == null) {
            state = "error";
            error = "اجازه‌ی ضبط صفحه داده نشد";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            main.removeCallbacks(watcher);
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (thread != null) thread.quitSafely();
            display = null;
            reader = null;
            if (projection != null) projection.stop();
            final MediaProjection mp = mpm.getMediaProjection(code, data);
            projection = mp;
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (projection != mp) return; // جلسه‌ی قدیمی
                    Diag.log("اشتراک صفحه توسط سیستم/کاربر متوقف شد");
                    main.post(ScreenService.this::stopSelf);
                }
            }, main);
            instance = this;
            thread = new HandlerThread("payambin-screen");
            thread.start();
            h = new Handler(thread.getLooper());
            createDisplay();
            state = "on";
            main.postDelayed(watcher, 1000);
            Diag.log("اشتراک صفحه شروع شد " + capW + "x" + capH);
        } catch (Exception e) {
            state = "error";
            error = e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 12,
                new Intent(this, ScreenService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("🖥 صفحه‌ی گوشی با کامپیوتر به اشتراک گذاشته شده")
                .setContentText("لمس کنید تا قطع شود")
                .setOngoing(true)
                .setContentIntent(stop)
                .addAction(new Notification.Action.Builder(null, "قطع اشتراک صفحه", stop).build())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void readRealSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        realW = m.widthPixels;
        realH = m.heightPixels;
        dpi = m.densityDpi;
    }

    private void computeCapSize() {
        int target = quality.equals("low") ? 540 : quality.equals("high") ? 1080 : 720; // ضلع کوچک‌تر
        int shortSide = Math.min(realW, realH);
        float scale = Math.min(1f, target / (float) shortSide);
        capW = Math.round(realW * scale) & ~1;
        capH = Math.round(realH * scale) & ~1;
    }

    private void createDisplay() {
        readRealSize();
        computeCapSize();
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, h);
        display = projection.createVirtualDisplay("PayamBin", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
    }

    /** چرخش گوشی یا تغییر کیفیت → اندازه‌ی جدید */
    private int lastQualityHash = 0;

    private void checkSize() {
        if (display == null) return;
        int oldW = realW, oldH = realH;
        readRealSize();
        int qh = quality.hashCode();
        if (oldW == realW && oldH == realH && qh == lastQualityHash) return;
        lastQualityHash = qh;
        computeCapSize();
        h.post(() -> {
            try {
                ImageReader old = reader;
                reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(this::onImage, h);
                display.resize(capW, capH, dpi);
                display.setSurface(paused ? null : reader.getSurface());
                if (old != null) old.close();
                lastBitmap = null;
            } catch (Exception e) {
                Diag.log("تغییر اندازه‌ی صفحه: " + e.getMessage());
            }
        });
    }

    /** وقتی کسی تماشا نمی‌کند، تصویر گرفته نمی‌شود (ولی اجازه‌ی ضبط زنده می‌ماند) */
    private void setPaused(boolean p) {
        if (p == paused || display == null) return;
        paused = p;
        h.post(() -> {
            try {
                display.setSurface(p ? null : reader.getSurface());
            } catch (Exception ignored) {
            }
        });
    }

    // ---- کم کردن نور صفحه‌ی گوشی هنگام تماشا ----
    private int savedBrightness = -1, savedMode = -1;

    public static boolean canDim(Context c) {
        return Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(c);
    }

    private void dimOn() {
        if (savedBrightness >= 0 || !canDim(this)) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            savedMode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            savedBrightness = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            getSharedPreferences("payambin", MODE_PRIVATE).edit()
                    .putInt("dim_saved_b", savedBrightness).putInt("dim_saved_m", savedMode).apply();
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 1);
            Diag.log("نور صفحه‌ی گوشی کم شد (تماشا از کامپیوتر)");
        } catch (Exception e) {
            savedBrightness = -1;
        }
    }

    private void dimOff() {
        if (savedBrightness < 0) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
            if (savedMode >= 0) android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
        } catch (Exception ignored) {
        }
        savedBrightness = -1;
        savedMode = -1;
        getSharedPreferences("payambin", MODE_PRIVATE).edit().remove("dim_saved_b").remove("dim_saved_m").apply();
    }

    /** اگر برنامه وسط تماشا بسته شده بود، نور قبلی را برگردان */
    public static void restoreBrightnessIfNeeded(Context c) {
        if (instance != null) return;
        android.content.SharedPreferences sp = c.getSharedPreferences("payambin", MODE_PRIVATE);
        int b = sp.getInt("dim_saved_b", -1), m = sp.getInt("dim_saved_m", -1);
        if (b < 0 || !canDim(c)) return;
        try {
            android.provider.Settings.System.putInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, b);
            if (m >= 0) android.provider.Settings.System.putInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, m);
        } catch (Exception ignored) {
        }
        sp.edit().remove("dim_saved_b").remove("dim_saved_m").apply();
    }

    @SuppressWarnings("deprecation")
    private void acquireScreen(boolean wakeNow) {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (screenLock == null) {
                screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PayamBin::screen");
                screenLock.setReferenceCounted(false);
            }
            if (wakeNow || !screenLock.isHeld()) screenLock.acquire(10 * 60 * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void onImage(ImageReader r) {
        Image im = null;
        try {
            im = r.acquireLatestImage();
            if (im == null) return;
            long now = System.currentTimeMillis();
            if (viewers.get() == 0 || now - lastFrame < 1000 / Math.max(2, Math.min(30, fps))) return;
            lastFrame = now;
            Image.Plane p = im.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pixelStride = p.getPixelStride(), rowStride = p.getRowStride();
            int w = im.getWidth(), hh = im.getHeight();
            int bw = rowStride / pixelStride;
            if (lastBitmap == null || lastBitmap.getWidth() != bw || lastBitmap.getHeight() != hh)
                lastBitmap = Bitmap.createBitmap(bw, hh, Bitmap.Config.ARGB_8888);
            buf.rewind();
            lastBitmap.copyPixelsFromBuffer(buf);
            im.close();
            im = null;
            Bitmap out = bw == w ? lastBitmap : Bitmap.createBitmap(lastBitmap, 0, 0, w, hh);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(w * hh / 8);
            out.compress(Bitmap.CompressFormat.JPEG, quality.equals("high") ? 75 : 60, bo);
            if (out != lastBitmap) out.recycle();
            synchronized (LOCK) {
                frame = bo.toByteArray();
                seq++;
                LOCK.notifyAll();
            }
        } catch (Exception ignored) {
        } finally {
            if (im != null) im.close();
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        dimOff();
        try {
            if (display != null) display.release();
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        if (thread != null) thread.quitSafely();
        display = null;
        projection = null;
        instance = null;
        if (!"error".equals(state)) state = "off";
        synchronized (LOCK) {
            frame = null;
            seq++;
            LOCK.notifyAll();
        }
        Diag.log("اشتراک صفحه تمام شد");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
