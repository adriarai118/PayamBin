package com.adriarai.payambin;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** گزارش عیب‌یابی: رویدادها با ساعت در فایل ذخیره می‌شوند (بعد از بسته شدن برنامه هم می‌ماند) */
public final class Diag {
    private Diag() {}

    private static final long MAX = 150_000;
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static synchronized void init(Context c) {
        if (file == null) file = new File(c.getApplicationContext().getFilesDir(), "diag.log");
    }

    public static synchronized void log(String msg) {
        if (file == null) return;
        try {
            if (file.length() > MAX) trim();
            FileOutputStream out = new FileOutputStream(file, true);
            out.write((FMT.format(new Date()) + "  " + msg + "\n").getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void trim() throws Exception {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        long keep = MAX / 2;
        f.seek(f.length() - keep);
        byte[] b = new byte[(int) keep];
        f.readFully(b);
        f.close();
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(b);
        out.close();
    }

    /** آخرین n بایت گزارش */
    public static synchronized String read(int maxBytes) {
        if (file == null || !file.exists()) return "";
        try {
            RandomAccessFile f = new RandomAccessFile(file, "r");
            long start = Math.max(0, f.length() - maxBytes);
            f.seek(start);
            byte[] b = new byte[(int) (f.length() - start)];
            f.readFully(b);
            f.close();
            String s = new String(b, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized void clear() {
        if (file != null) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }
}
