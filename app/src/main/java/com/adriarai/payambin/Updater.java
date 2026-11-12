package com.adriarai.payambin;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** بررسی Releases گیت‌هاب، دانلود و نصب نسخهٔ جدید */
public final class Updater {
    private Updater() {}

    private static final String API = "https://api.github.com/repos/adriarai118/PayamBin/releases/latest";
    private static final long INTERVAL = 6 * 60 * 60 * 1000L; // هر ۶ ساعت

    public static volatile String status = "";
    private static volatile boolean busy = false;

    /** از سرویس صدا زده می‌شود؛ اگر وقتش رسیده بود بررسی می‌کند */
    public static void maybeCheck(Context c) {
        long last = Prefs.lastUpdateCheck(c);
        if (System.currentTimeMillis() - last > INTERVAL) checkAsync(c);
    }

    public static void checkAsync(Context c) {
        if (busy) return;
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                check(app);
            } catch (Exception e) {
                status = "خطا در بررسی آپدیت: " + e.getMessage();
            } finally {
                busy = false;
            }
        }, "payambin-update").start();
    }

    @SuppressWarnings("deprecation")
    public static long currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void check(Context c) throws Exception {
        status = "در حال بررسی آپدیت…";
        Prefs.setLastUpdateCheck(c, System.currentTimeMillis());
        JSONObject rel = new JSONObject(new String(get(API), StandardCharsets.UTF_8));
        long remote = 0;
        String digits = rel.optString("tag_name").replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) remote = Long.parseLong(digits);
        long cur = currentVersion(c);
        if (remote <= cur) {
            status = "آخرین نسخه نصب است (نسخهٔ " + cur + ")";
            return;
        }
        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url");
                    break;
                }
            }
        }
        if (url == null) {
            status = "نسخهٔ " + remote + " فایل APK ندارد";
            return;
        }
        status = "در حال دانلود نسخهٔ " + remote + "…";
        File f = new File(c.getCacheDir(), "update.apk");
        download(url, f);
        status = "در حال نصب نسخهٔ " + remote + "…";
        install(c, f);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "PayamBin-Updater");
        h.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = h.getResponseCode();
        if (code == 404) throw new IOException("مخزن یا نسخه پیدا نشد (مخزن باید Public باشد)");
        if (code != 200) throw new IOException("HTTP " + code);
        return h;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // اندروید ۱۲+: اگر خود برنامه نصب‌کنندهٔ قبلی باشد، بدون سؤال نصب می‌شود
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent cb = PendingIntent.getBroadcast(c, id, new Intent(c, UpdateReceiver.class), flags);
            s.commit(cb.getIntentSender());
        } finally {
            s.close();
        }
    }
}
