package com.adriarai.payambin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * فایل‌های رد و بدل شده:
 * inbox  = از کامپیوتر به گوشی (در پوشه‌ی Download/PayamBin هم کپی می‌شود)
 * outbox = از گوشی به کامپیوتر (با «اشتراک‌گذاری ← پیام‌بین»)
 */
public final class FileStore {
    private FileStore() {}

    public static File dir(Context c, String box) {
        File d = new File(c.getFilesDir(), box.equals("out") ? "outbox" : "inbox");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** نام امن و یکتا */
    public static File newFile(Context c, String box, String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "file";
        if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
        File d = dir(c, box);
        File f = new File(d, safe);
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        int i = 2;
        while (f.exists()) f = new File(d, base + " (" + (i++) + ")" + ext);
        return f;
    }

    public static File get(Context c, String box, String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.equals("..")) return null;
        File f = new File(dir(c, box), name);
        return f.isFile() ? f : null;
    }

    public static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    public static JSONArray list(Context c, String box) {
        File[] fs = dir(c, box).listFiles();
        JSONArray a = new JSONArray();
        if (fs == null) return a;
        Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (File f : fs) {
            if (!f.isFile() || f.getName().endsWith(".part")) continue;
            a.put(J.obj("name", f.getName(), "size", f.length(), "time", f.lastModified(), "mime", mime(f.getName())));
        }
        return a;
    }

    public static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536];
        long left = limit;
        int n;
        while ((limit < 0 || left > 0) && (n = in.read(buf, 0, limit < 0 ? buf.length : (int) Math.min(buf.length, left))) > 0) {
            out.write(buf, 0, n);
            left -= n;
        }
        if (limit >= 0 && left > 0) throw new IOException("ناقص");
    }

    /** ذخیره‌ی فایل آپلود‌شده از کامپیوتر */
    public static File receive(Context c, String name, InputStream in, long len) throws IOException {
        File f = newFile(c, "in", name);
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            copy(in, out, len);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(f)) throw new IOException("rename failed");
        copyToDownloads(c, f);
        NotifStore.Versions.files.incrementAndGet();
        Diag.log("فایل از کامپیوتر رسید: " + f.getName() + " (" + f.length() / 1024 + " KB)");
        return f;
    }

    /** کپی در Download/PayamBin تا در «فایل‌ها»ی گوشی دیده شود (اندروید ۱۰+) */
    private static void copyToDownloads(Context c, File f) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(f.getName()));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PayamBin");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return;
            try (OutputStream out = c.getContentResolver().openOutputStream(u);
                 InputStream in = new FileInputStream(f)) {
                if (out != null) copy(in, out, -1);
            }
        } catch (Exception e) {
            Diag.log("کپی در Download نشد: " + e.getMessage());
        }
    }

    public static Uri shareUri(String box, String name) {
        return Uri.parse("content://" + FilesProvider.AUTHORITY + "/" + box + "/" + Uri.encode(name));
    }

    private static final String CHANNEL = "share";

    /** اعلان روی گوشی: «لمس کنید تا در بله/واتس‌اپ فرستاده شود» */
    public static void offerShare(Context c, String name, String pkg, String caption) {
        File f = get(c, "in", name);
        if (f == null) throw new IllegalArgumentException("فایل پیدا نشد");
        Uri uri = shareUri("in", name);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime(name));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        if (caption != null && !caption.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, caption);
        send.setClipData(ClipData.newRawUri(name, uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        String appName = "برنامه";
        boolean installed = false;
        if (pkg != null && !pkg.isEmpty()) {
            try {
                PackageManager pm = c.getPackageManager();
                appName = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                installed = true;
            } catch (Exception ignored) {
            }
        }
        Intent target;
        if (installed) {
            send.setPackage(pkg);
            target = send;
        } else {
            target = Intent.createChooser(send, "فرستادن " + name);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "فرستادن فایل", NotificationManager.IMPORTANCE_HIGH));
        int id = 1000 + (name.hashCode() & 0xfff);
        PendingIntent pi = PendingIntent.getActivity(c, id, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        @SuppressWarnings("deprecation")
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("📎 فرستادن در " + (installed ? appName : "…"))
                .setContentText(name + " — لمس کنید و گفتگو را انتخاب کنید")
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(id, b.build());
    }
}
