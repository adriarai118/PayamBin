package com.adriarai.payambin;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** هر کرش برنامه را با جزئیات در گزارش عیب‌یابی و فایل crash.txt ثبت می‌کند */
public final class Crash {
    private Crash() {}

    private static boolean installed = false;

    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        Diag.init(app);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String trace = trace(e);
                Diag.log("💥 کرش در رشتهٔ «" + t.getName() + "» نسخهٔ " + Updater.currentVersion(app) + "\n" + trace);
                FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), "crash.txt"), false);
                out.write((System.currentTimeMillis() + "\n" + trace).getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static String trace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 6000 ? s.substring(0, 6000) : s;
    }
}
