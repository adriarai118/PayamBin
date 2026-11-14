package com.adriarai.payambin;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * «خوانده شد» برای پیامک‌ها.
 * اندروید فقط به برنامهٔ پیامک پیش‌فرض اجازهٔ تغییر وضعیت پیامک‌ها را می‌دهد؛ پس:
 * ۱) وضعیت خوانده‌شده را خود پیام‌بین برای هر گفتگو نگه می‌دارد (تا چه زمانی خوانده شده)،
 * ۲) دکمهٔ «خوانده شد» اعلانِ برنامهٔ پیامک گوشی را می‌زند (پیامک در خود برنامهٔ پیامک هم خوانده می‌شود)،
 * ۳) اگر گوشی اجازه داد، مستقیم هم در پایگاه پیامک‌ها علامت می‌زند.
 */
public final class SmsRead {
    private SmsRead() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sms_read", Context.MODE_PRIVATE);
    }

    /** پیامک‌های دریافتی تا این زمان در این گفتگو خوانده‌شده حساب می‌شوند */
    public static long readUpTo(Context c, long thread) {
        return sp(c).getLong("t" + thread, 0);
    }

    public static void mark(Context c, long thread, String address, boolean phone) {
        long now = System.currentTimeMillis();
        sp(c).edit().putLong("t" + thread, now).apply();
        if (phone) {
            try {
                ContentValues v = new ContentValues();
                v.put("read", 1);
                v.put("seen", 1);
                c.getContentResolver().update(Uri.parse("content://sms/inbox"), v,
                        "thread_id=? AND read=0", new String[]{String.valueOf(thread)});
            } catch (Throwable ignored) {
            }
            String name = SmsRepo.contactName(c, address);
            NotifListener.markSmsRead(c, address, name);
        }
        NotifStore.Versions.sms.incrementAndGet();
    }

    /** کلید مقایسهٔ شماره‌ها: ۱۰ رقم آخر */
    public static String key(String s) {
        if (s == null) return "";
        String d = Otp.latinDigits(s).replaceAll("[^0-9]", "");
        if (d.length() >= 7) return "n" + (d.length() > 10 ? d.substring(d.length() - 10) : d);
        return "s" + s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
