package com.adriarai.payambin;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/** تنظیمات ساده: رمز ورود، پورت، توکن‌های ورود مرورگرها، شروع خودکار */
public final class Prefs {
    private static final String NAME = "payambin";
    private static final SecureRandom RND = new SecureRandom();

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String pin(Context c) {
        String p = sp(c).getString("pin", null);
        if (p == null) p = newPin(c);
        return p;
    }

    public static String newPin(Context c) {
        String p = String.valueOf(100000 + RND.nextInt(900000));
        // با تغییر رمز، همهٔ مرورگرهای واردشده خارج می‌شوند
        sp(c).edit().putString("pin", p).putStringSet("tokens", new HashSet<String>()).apply();
        return p;
    }

    public static int port(Context c) {
        return sp(c).getInt("port", 8080);
    }

    public static void setPort(Context c, int port) {
        sp(c).edit().putInt("port", port).apply();
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }

    /** آیا سرور باید روشن باشد (کاربر آن را خاموش نکرده) */
    public static boolean wantRunning(Context c) {
        return sp(c).getBoolean("want_running", false);
    }

    public static void setWantRunning(Context c, boolean v) {
        sp(c).edit().putBoolean("want_running", v).apply();
    }

    /** کلید API برای اسکریپت‌های کامپیوتر (فقط /api/otp) */
    public static String apiKey(Context c) {
        String k = sp(c).getString("api_key", null);
        if (k == null) k = newApiKey(c);
        return k;
    }

    public static String newApiKey(Context c) {
        byte[] b = new byte[12];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        sp(c).edit().putString("api_key", sb.toString()).apply();
        return sb.toString();
    }

    // ---------- اشتراک اینترنت (پروکسی) ----------
    public static boolean proxyOn(Context c) {
        return sp(c).getBoolean("proxy_on", false);
    }

    public static void setProxyOn(Context c, boolean v) {
        sp(c).edit().putBoolean("proxy_on", v).apply();
    }

    /** فقط دستگاه‌هایی که وارد صفحه‌ی پیام‌بین شده‌اند (پیش‌فرض: خاموش = همه‌ی شبکه‌ی داخلی) */
    public static boolean proxyRestrict(Context c) {
        return sp(c).getBoolean("proxy_restrict", false);
    }

    public static void setProxyRestrict(Context c, boolean v) {
        sp(c).edit().putBoolean("proxy_restrict", v).apply();
    }

    /** سقف مصرف روزانه (مگابایت، ۰ = نامحدود) */
    public static int proxyCapMb(Context c) {
        return sp(c).getInt("proxy_cap_mb", 0);
    }

    public static void setProxyCapMb(Context c, int mb) {
        sp(c).edit().putInt("proxy_cap_mb", Math.max(0, mb)).apply();
    }

    /** ساعت کاری: فقط در این بازه روشن باشد */
    public static boolean proxySched(Context c) {
        return sp(c).getBoolean("proxy_sched", false);
    }

    public static String proxyStart(Context c) {
        return sp(c).getString("proxy_start", "07:30");
    }

    public static String proxyEnd(Context c) {
        return sp(c).getString("proxy_end", "14:30");
    }

    public static void setProxySched(Context c, boolean on, String start, String end) {
        SharedPreferences.Editor e = sp(c).edit().putBoolean("proxy_sched", on);
        if (start != null && start.matches("\\d{1,2}:\\d{2}")) e.putString("proxy_start", start);
        if (end != null && end.matches("\\d{1,2}:\\d{2}")) e.putString("proxy_end", end);
        e.apply();
    }

    /** مصرف امروز (JSON) */
    public static String proxyUsage(Context c) {
        return sp(c).getString("proxy_usage", "");
    }

    public static void setProxyUsage(Context c, String json) {
        sp(c).edit().putString("proxy_usage", json).apply();
    }

    public static int proxyPort(Context c) {
        return sp(c).getInt("proxy_port", 8888);
    }

    public static void setProxyPort(Context c, int p) {
        sp(c).edit().putInt("proxy_port", p).apply();
    }

    public static Set<String> proxyClients(Context c) {
        return new HashSet<>(sp(c).getStringSet("proxy_clients", new HashSet<String>()));
    }

    public static void setProxyClients(Context c, Set<String> s) {
        sp(c).edit().putStringSet("proxy_clients", new HashSet<>(s)).apply();
    }

    /** تم برنامه: system | light | dark */
    public static String theme(Context c) {
        return sp(c).getString("theme", "system");
    }

    public static void setTheme(Context c, String t) {
        sp(c).edit().putString("theme", t).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("last_update_check", 0);
    }

    public static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("last_update_check", t).apply();
    }

    public static synchronized String newToken(Context c) {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String t = sb.toString();
        Set<String> s = new HashSet<>(sp(c).getStringSet("tokens", new HashSet<String>()));
        if (s.size() > 50) s.clear();
        s.add(t);
        sp(c).edit().putStringSet("tokens", s).apply();
        return t;
    }

    public static boolean validToken(Context c, String t) {
        if (t == null || t.isEmpty()) return false;
        return sp(c).getStringSet("tokens", new HashSet<String>()).contains(t);
    }

    public static synchronized void removeToken(Context c, String t) {
        Set<String> s = new HashSet<>(sp(c).getStringSet("tokens", new HashSet<String>()));
        s.remove(t);
        sp(c).edit().putStringSet("tokens", s).apply();
    }
}
