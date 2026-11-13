package com.adriarai.payambin;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** پخش زنده‌ی دوربین گوشی برای کامپیوتر (MJPEG) */
public class CameraService extends Service {
    public static final String ACTION_START = "com.adriarai.payambin.CAM_START";
    public static final String ACTION_STOP = "com.adriarai.payambin.CAM_STOP";       // غیرفعال کامل
    public static final String ACTION_STANDBY = "com.adriarai.payambin.CAM_STANDBY"; // فقط آماده (بدون روشن کردن دوربین)
    private static final int NOTIF_ID = 88, ASK_ID = 89;
    private static final String CH = "camera";

    // ---- وضعیت مشترک ----
    public static volatile String state = "off";   // off | ready | waiting_tap | starting | on | error
    public static volatile String error = null;
    public static volatile String facing = "back";
    public static volatile String quality = "mid";   // low | mid | high
    public static volatile boolean torch = false;
    public static volatile int targetFps = 24;
    public static volatile double actualFps = 0;
    public static volatile boolean hasFlash = false;
    public static volatile int orientation = 90;
    public static volatile int width = 0, height = 0;
    public static final AtomicInteger viewers = new AtomicInteger();
    public static final Object LOCK = new Object();
    public static volatile byte[] frame;
    public static volatile long seq = 0;
    private static volatile long lastViewerTime = 0;
    private static CameraService instance;

    // ---- صدا (میکروفون) ----
    public static final int RATE = 24000;
    public static final AtomicInteger audioViewers = new AtomicInteger();
    public static final Object ALOCK = new Object();
    public static final int RING = 64;
    public static final byte[][] aring = new byte[RING][];
    public static volatile long aseq = 0;
    public static volatile String audioState = "off"; // off | on | error
    public static volatile boolean noiseSuppress = false;
    private volatile Thread audioThread;
    private volatile boolean audioRun;

    /** شروع میکروفون برای یک شنونده؛ اگر ممکن نبود دلیلش را برمی‌گرداند */
    public static volatile boolean micWaiting = false;

    /** null = شروع شد؛ "WAIT" = منتظر لمس روی گوشی؛ در غیر این صورت دلیل خطا */
    public static String audioStart(Context c) {
        if (!SmsRepo.has(c, Manifest.permission.RECORD_AUDIO)) return "مجوز میکروفون روی گوشی داده نشده (برنامه ← مجوزها)";
        CameraService s = instance;
        if (s == null) {
            if (!micWaiting) {
                micWaiting = true;
                Context app = c.getApplicationContext();
                Intent i = new Intent(app, CameraService.class).setAction(ACTION_STANDBY).putExtra("mic", true);
                // اندروید ۱۱+ میکروفونِ شروع‌شده از پس‌زمینه را بی‌صدا می‌کند → فقط وقتی برنامه جلوی چشم است مستقیم شروع کن
                if (Build.VERSION.SDK_INT >= 30 && !MainActivity.visible) askTap(app, true);
                else try {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                } catch (Exception e) {
                    askTap(app, true);
                }
            }
            return "WAIT";
        }
        micWaiting = false;
        s.ensureAudio();
        return null;
    }

    private synchronized void ensureAudio() {
        if (audioThread != null) return;
        audioRun = true;
        audioThread = new Thread(this::audioLoop, "payambin-mic");
        audioThread.start();
    }

    @SuppressWarnings("MissingPermission")
    private void audioLoop() {
        android.media.AudioRecord ar = null;
        try {
            int min = android.media.AudioRecord.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            ar = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE / 2));
            if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("میکروفون در دسترس نیست");
            // کاهش نویز سخت‌افزاری گوشی (اگر پشتیبانی شود)
            android.media.audiofx.NoiseSuppressor ns = null;
            android.media.audiofx.AutomaticGainControl agc = null;
            boolean nsWanted = noiseSuppress;
            if (nsWanted) {
                try {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(ar.getAudioSessionId());
                        if (ns != null) ns.setEnabled(true);
                    }
                    if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                        agc = android.media.audiofx.AutomaticGainControl.create(ar.getAudioSessionId());
                        if (agc != null) agc.setEnabled(true);
                    }
                } catch (Exception ignored) {
                }
            }
            ar.startRecording();
            audioState = "on";
            main.post(this::updateNotification);
            Diag.log("میکروفون روشن شد");
            long idleSince = 0;
            while (audioRun) {
                byte[] buf = new byte[RATE / 25 * 2]; // ۴۰ میلی‌ثانیه
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (n < buf.length) buf = java.util.Arrays.copyOf(buf, n);
                synchronized (ALOCK) {
                    aring[(int) (aseq % RING)] = buf;
                    aseq++;
                    ALOCK.notifyAll();
                }
                long now = System.currentTimeMillis();
                if (noiseSuppress != nsWanted) break; // تنظیم عوض شد → دوباره با تنظیم جدید
                if (audioViewers.get() == 0) {
                    if (idleSince == 0) idleSince = now;
                    else if (now - idleSince > 5000) break; // کسی گوش نمی‌دهد
                } else idleSince = 0;
            }
            audioState = "off";
        } catch (Exception e) {
            audioState = "error";
            Diag.log("میکروفون: " + e.getMessage());
        } finally {
            if (ar != null) {
                try {
                    ar.stop();
                } catch (Exception ignored) {
                }
                ar.release();
            }
            synchronized (this) {
                audioThread = null;
            }
            if (audioRun && audioViewers.get() > 0 && "off".equals(audioState)) ensureAudio();
            synchronized (ALOCK) {
                ALOCK.notifyAll();
            }
            main.post(this::updateNotification);
            Diag.log("میکروفون خاموش شد");
        }
    }

    private HandlerThread thread;
    private Handler camHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private long lastFrame = 0;
    private final java.util.concurrent.ExecutorService encoder = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean encoding = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int fpsCount = 0;
    private long fpsStart = 0;
    private android.util.Range<Integer> aeRange;
    private boolean fromTap = false;
    private volatile boolean camOpen = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (viewers.get() > 0) lastViewerTime = System.currentTimeMillis();
            else if (camOpen && System.currentTimeMillis() - lastViewerTime > 45_000 && !(!Prefs.camStandby(CameraService.this) && audioViewers.get() > 0)) {
                Diag.log("دوربین: کسی تماشا نمی‌کند، خاموش شد");
                if (!goStandby()) return;
            }
            main.postDelayed(this, 5000);
        }
    };

    // ---------------- درخواست از کامپیوتر ----------------

    public static void request(Context c) {
        Context app = c.getApplicationContext();
        lastViewerTime = System.currentTimeMillis();
        error = null;
        if (!SmsRepo.has(app, Manifest.permission.CAMERA)) {
            state = "error";
            error = "مجوز دوربین روی گوشی داده نشده (برنامه ← مجوزها)";
            return;
        }
        if (instance != null) {
            state = "starting";
            CameraService s = instance;
            s.camHandler.post(s::reopen);
            s.main.post(s::updateNotification);
            return;
        }
        state = "starting";
        Intent i = new Intent(app, CameraService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception e) {
            askTap(app);
        }
    }

    /** خاموش کردن دوربین از کامپیوتر: اگر «همیشه آماده» روشن است فقط دوربین بسته می‌شود */
    public static void stop(Context c) {
        CameraService s = instance;
        if (s != null && Prefs.camStandby(c)) {
            s.main.post(s::goStandby);
            return;
        }
        c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), CameraService.class));
        state = "off";
    }

    /** از MainActivity (برنامه جلوی چشم است → اندروید اجازه می‌دهد) */
    public static void ensureStandby(Context c) {
        if (instance != null || !Prefs.camStandby(c) || !SmsRepo.has(c, Manifest.permission.CAMERA)) return;
        try {
            Intent i = new Intent(c, CameraService.class).setAction(ACTION_STANDBY);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void disable(Context c) {
        c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), CameraService.class));
        state = "off";
    }

    /** دوربین را ببند ولی سرویس را آماده نگه دار؛ اگر «همیشه آماده» خاموش است کل سرویس را ببند */
    private boolean goStandby() {
        if (!Prefs.camStandby(this)) {
            stopSelf();
            return false;
        }
        camHandler.post(() -> {
            closeCamera();
            torch = false;
            actualFps = 0;
            state = "ready";
            synchronized (LOCK) {
                frame = null;
                seq++;
                LOCK.notifyAll();
            }
            main.post(this::updateNotification);
        });
        return true;
    }

    private void updateNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    public static void setTorch(boolean on) {
        torch = on;
        CameraService s = instance;
        if (s != null) s.camHandler.post(s::applyRequest);
    }

    public static JSONObject status() {
        return J.obj("state", state, "error", error, "facing", facing, "quality", quality, "torch", torch,
                "audio", audioState, "noiseSuppress", noiseSuppress,
                "fps", targetFps, "actualFps", Math.round(actualFps * 10) / 10.0,
                "hasFlash", hasFlash, "orientation", orientation, "width", width, "height", height,
                "viewers", viewers.get());
    }

    /** اعلان «لمس کنید تا دوربین روشن شود» (اندروید اجازه‌ی روشن کردن دوربین از پس‌زمینه را نمی‌دهد) */
    @SuppressWarnings("deprecation")
    static void askTap(Context c) {
        askTap(c, false);
    }

    @SuppressWarnings("deprecation")
    static void askTap(Context c, boolean micOnly) {
        if (!micOnly) state = "waiting_tap";
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel(CH + "_ask", "درخواست دوربین", NotificationManager.IMPORTANCE_HIGH));
            Intent i = new Intent(c, CameraService.class).setAction(micOnly ? ACTION_STANDBY : ACTION_START).putExtra("tap", true);
            int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 7, i, f)
                    : PendingIntent.getService(c, 7, i, f);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH + "_ask") : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(micOnly ? "🎙 کامپیوتر می‌خواهد صدای گوشی را بشنود" : "📷 کامپیوتر می‌خواهد دوربین را ببیند")
                    .setContentText(micOnly ? "برای روشن کردن میکروفون لمس کنید" : "برای روشن کردن دوربین لمس کنید")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .addAction(new Notification.Action.Builder(null, "روشن کن", pi).build());
            nm.notify(ASK_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    // ---------------- چرخه‌ی سرویس ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "پخش دوربین", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean standbyOnly = intent != null && ACTION_STANDBY.equals(intent.getAction());
        fromTap = intent != null && intent.getBooleanExtra("tap", false);
        if (fromTap) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ASK_ID);
            lastViewerTime = System.currentTimeMillis();
        }
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 30) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                if (SmsRepo.has(this, Manifest.permission.RECORD_AUDIO)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIF_ID, n, types);
            } else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            if (standbyOnly) {
                if (intent.getBooleanExtra("mic", false) && !fromTap) askTap(this, true);
                else micWaiting = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            // اندروید ۱۴+: از پس‌زمینه اجازه نمی‌دهد → از کاربر لمس بخواه
            Diag.log("دوربین از پس‌زمینه روشن نشد؛ درخواست لمس روی گوشی");
            if (!fromTap) askTap(this);
            else {
                state = "error";
                error = "اندروید اجازه‌ی روشن کردن دوربین را نداد: " + e.getMessage();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (instance == null) {
            instance = this;
            thread = new HandlerThread("payambin-cam");
            thread.start();
            camHandler = new Handler(thread.getLooper());
            main.postDelayed(idleCheck, 5000);
            micWaiting = false;
            if (standbyOnly) {
                state = "ready";
                Diag.log("دوربین در حالت آماده (بدون نیاز به تأیید)");
            } else camHandler.post(this::open);
        } else if (!standbyOnly && !camOpen) {
            camHandler.post(this::reopen);
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 8,
                new Intent(this, CameraService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        boolean live = camOpen || "starting".equals(state);
        boolean micLive = "on".equals(audioState);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(live ? (micLive ? "📷🎙 دوربین و میکروفون در حال پخش است" : "📷 دوربین برای کامپیوتر در حال پخش است")
                        : micLive ? "🎙 میکروفون برای کامپیوتر در حال پخش است" : "📷 دوربین و میکروفون آماده است (خاموش)")
                .setContentText(live ? "برای قطع و غیرفعال کردن لمس کنید" : "از کامپیوتر بدون تأیید روشن می‌شود — لمس: غیرفعال کردن")
                .setOngoing(true)
                .setContentIntent(stop)
                .addAction(new Notification.Action.Builder(null, live || micLive ? "قطع" : "غیرفعال کردن", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (camHandler != null) {
            camHandler.post(() -> {
                closeCamera();
                thread.quitSafely();
            });
        }
        audioRun = false;
        encoder.shutdownNow();
        instance = null;
        state = "off";
        torch = false;
        actualFps = 0;
        synchronized (LOCK) {
            frame = null;
            seq++;
            LOCK.notifyAll();
        }
        Diag.log("دوربین خاموش شد");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- دوربین ----------------

    private void reopen() {
        closeCamera();
        open();
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        device = null;
        reader = null;
        camOpen = false;
    }

    private String pickCamera(CameraManager cm) throws CameraAccessException {
        int want = facing.equals("front") ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String first = null;
        for (String id : cm.getCameraIdList()) {
            if (first == null) first = id;
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == want) return id;
        }
        return first;
    }

    private Size pickSize(StreamConfigurationMap map) {
        int target = quality.equals("low") ? 640 : quality.equals("high") ? 1920 : 1280;
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() > target) continue;
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : map.getOutputSizes(ImageFormat.YUV_420_888)[0];
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void open() {
        state = "starting";
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = pickCamera(cm);
            if (id == null) throw new IllegalStateException("دوربینی پیدا نشد");
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = so == null ? 90 : so;
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            if (!hasFlash) torch = false;
            aeRange = null;
            android.util.Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) {
                // بالاترین حد پایین که از هدف بیشتر نشود، با حد بالای ≥ هدف
                for (android.util.Range<Integer> r : ranges) {
                    if (r.getUpper() < Math.min(targetFps, 30) || r.getLower() > targetFps) continue;
                    if (aeRange == null || r.getLower() > aeRange.getLower()
                            || (r.getLower().equals(aeRange.getLower()) && r.getUpper() < aeRange.getUpper())) aeRange = r;
                }
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map);
            width = size.getWidth();
            height = size.getHeight();
            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(this::onImage, camHandler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                applyRequest();
                                camOpen = true;
                                state = "on";
                                main.post(CameraService.this::updateNotification);
                                Diag.log("دوربین روشن شد (" + facing + " " + width + "x" + height + ")");
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {
                                fail("پیکربندی دوربین ناموفق بود");
                            }
                        }, camHandler);
                    } catch (Exception e) {
                        fail(e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                    fail("دوربین توسط برنامه‌ی دیگری گرفته شد");
                }

                @Override
                public void onError(CameraDevice d, int err) {
                    d.close();
                    device = null;
                    if (!fromTap && (err == ERROR_CAMERA_DISABLED || err == ERROR_CAMERA_SERVICE)) {
                        // اندروید ۱۱–۱۳: دسترسی پس‌زمینه رد شد → لمس روی گوشی
                        main.post(() -> {
                            askTap(CameraService.this);
                            stopSelf();
                        });
                    } else fail("خطای دوربین (" + err + ")");
                }
            }, camHandler);
        } catch (SecurityException e) {
            fail("مجوز دوربین داده نشده");
        } catch (CameraAccessException e) {
            if (!fromTap) main.post(() -> {
                askTap(CameraService.this);
                stopSelf();
            });
            else fail("دسترسی به دوربین ممکن نشد: " + e.getMessage());
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void fail(String msg) {
        state = "error";
        error = msg;
        Diag.log("دوربین: " + msg);
    }

    private void applyRequest() {
        try {
            if (device == null || session == null) return;
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (aeRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeRange);
            builder.set(CaptureRequest.FLASH_MODE, torch && hasFlash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(builder.build(), null, camHandler);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    private void onImage(ImageReader r) {
        Image im = null;
        try {
            im = r.acquireLatestImage();
            if (im == null) return;
            long now = System.currentTimeMillis();
            int interval = 1000 / Math.max(5, Math.min(30, targetFps));
            if (viewers.get() == 0 || now - lastFrame < interval - 4) return;
            if (!encoding.compareAndSet(false, true)) return; // رمزگذار مشغول است → این فریم رد شود
            lastFrame = now;
            final int w = im.getWidth(), h = im.getHeight();
            final byte[] nv21 = toNv21(im); // باید قبل از بستن Image کپی شود
            im.close();
            im = null;
            final int q = quality.equals("low") ? 50 : quality.equals("high") ? 75 : 65;
            encoder.execute(() -> {
                try {
                    YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(w * h / 6);
                    yuv.compressToJpeg(new Rect(0, 0, w, h), q, bo);
                    synchronized (LOCK) {
                        frame = bo.toByteArray();
                        seq++;
                        LOCK.notifyAll();
                    }
                    long t = System.currentTimeMillis();
                    if (fpsStart == 0) fpsStart = t;
                    fpsCount++;
                    if (t - fpsStart >= 1000) {
                        actualFps = fpsCount * 1000.0 / (t - fpsStart);
                        fpsCount = 0;
                        fpsStart = t;
                    }
                } catch (Exception ignored) {
                } finally {
                    encoding.set(false);
                }
            });
        } catch (Exception ignored) {
            encoding.set(false);
        } finally {
            if (im != null) im.close();
        }
    }

    /** YUV_420_888 → NV21 با در نظر گرفتن row/pixel stride */
    static byte[] toNv21(Image img) {
        Image.Plane[] p = img.getPlanes();
        return Yuv.toNv21(img.getWidth(), img.getHeight(),
                p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(),
                p[1].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(),
                p[2].getBuffer(), p[2].getRowStride(), p[2].getPixelStride());
    }

}
