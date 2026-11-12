package com.adriarai.payambin;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** خواندن و ارسال پیامک از طریق ContentProvider سیستم */
public final class SmsRepo {
    private SmsRepo() {}

    private static final Uri SMS = Uri.parse("content://sms");
    private static final Map<String, String> nameCache = new ConcurrentHashMap<>();
    private static long nameCacheTime = 0;

    public static boolean has(Context c, String perm) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static class Cols {
        int id, thread, address, body, date, type, read, sub;

        Cols(Cursor c) {
            id = c.getColumnIndex("_id");
            thread = c.getColumnIndex("thread_id");
            address = c.getColumnIndex("address");
            body = c.getColumnIndex("body");
            date = c.getColumnIndex("date");
            type = c.getColumnIndex("type");
            read = c.getColumnIndex("read");
            sub = c.getColumnIndex("sub_id");
        }
    }

    private static String s(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    private static long l(Cursor c, int i) {
        return (i < 0 || c.isNull(i)) ? 0 : c.getLong(i);
    }

    private static JSONObject msg(Cursor c, Cols k) {
        return J.obj(
                "id", l(c, k.id),
                "thread", l(c, k.thread),
                "address", s(c, k.address),
                "body", s(c, k.body),
                "date", l(c, k.date),
                "type", (int) l(c, k.type),   // 1 دریافتی، 2 ارسالی، 3 پیش‌نویس، 4 صف، 5 ناموفق، 6 در انتظار
                "read", l(c, k.read) != 0,
                "sub", k.sub >= 0 ? l(c, k.sub) : -1);
    }

    /** فهرست گفتگوها (جدیدترین اول) */
    public static JSONArray threads(Context ctx, int maxThreads) {
        LinkedHashMap<Long, JSONObject> map = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SMS, null, null, null, "date DESC");
            if (c == null) return new JSONArray();
            Cols k = new Cols(c);
            int scanned = 0;
            while (c.moveToNext() && scanned < 20000) {
                scanned++;
                long tid = l(c, k.thread);
                int type = (int) l(c, k.type);
                if (type == 3) continue; // پیش‌نویس
                JSONObject t = map.get(tid);
                if (t == null) {
                    if (map.size() >= maxThreads) continue;
                    t = J.obj("thread", tid,
                            "address", s(c, k.address),
                            "snippet", s(c, k.body),
                            "date", l(c, k.date),
                            "lastId", l(c, k.id),
                            "lastType", type,
                            "count", 0,
                            "unread", 0);
                    map.put(tid, t);
                }
                J.put(t, "count", t.optInt("count") + 1);
                if (type == 1 && l(c, k.read) == 0) J.put(t, "unread", t.optInt("unread") + 1);
            }
        } finally {
            if (c != null) c.close();
        }
        JSONArray out = new JSONArray();
        for (JSONObject t : map.values()) {
            J.put(t, "name", contactName(ctx, t.optString("address")));
            out.put(t);
        }
        return out;
    }

    /** پیام‌های یک گفتگو (قدیمی به جدید) */
    public static JSONArray messages(Context ctx, long threadId, int limit) {
        List<JSONObject> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SMS, null, "thread_id=?",
                    new String[]{String.valueOf(threadId)}, "date DESC");
            if (c == null) return new JSONArray();
            Cols k = new Cols(c);
            while (c.moveToNext() && list.size() < limit) {
                if ((int) l(c, k.type) == 3) continue;
                list.add(msg(c, k));
            }
        } finally {
            if (c != null) c.close();
        }
        Collections.reverse(list);
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out;
    }

    /** جستجو در متن و شماره */
    public static JSONArray search(Context ctx, String q, int limit) {
        JSONArray out = new JSONArray();
        Cursor c = null;
        try {
            String like = "%" + q + "%";
            c = ctx.getContentResolver().query(SMS, null, "body LIKE ? OR address LIKE ?",
                    new String[]{like, like}, "date DESC");
            if (c == null) return out;
            Cols k = new Cols(c);
            while (c.moveToNext() && out.length() < limit) {
                JSONObject m = msg(c, k);
                J.put(m, "name", contactName(ctx, m.optString("address")));
                out.put(m);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public static String contactName(Context ctx, String number) {
        if (number == null || number.isEmpty()) return null;
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null;
        long now = System.currentTimeMillis();
        if (now - nameCacheTime > 10 * 60 * 1000) {
            nameCache.clear();
            nameCacheTime = now;
        }
        String cached = nameCache.get(number);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = "";
        Cursor c = null;
        try {
            Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = ctx.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) name = n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        nameCache.put(number, name);
        return name.isEmpty() ? null : name;
    }

    /** سیم‌کارت‌های فعال (برای گوشی‌های دو سیم) */
    public static JSONArray sims(Context ctx) {
        JSONArray out = new JSONArray();
        if (Build.VERSION.SDK_INT < 22 || !has(ctx, Manifest.permission.READ_PHONE_STATE)) return out;
        try {
            SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> list = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (list == null) return out;
            for (SubscriptionInfo si : list) {
                out.put(J.obj("id", si.getSubscriptionId(),
                        "slot", si.getSimSlotIndex() + 1,
                        "name", String.valueOf(si.getDisplayName()),
                        "carrier", String.valueOf(si.getCarrierName())));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    public static void send(Context ctx, String address, String body, int subId) {
        SmsManager sm = null;
        if (subId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) {
                SmsManager base = ctx.getSystemService(SmsManager.class);
                if (base != null) sm = base.createForSubscriptionId(subId);
            }
            if (sm == null && Build.VERSION.SDK_INT >= 22) sm = SmsManager.getSmsManagerForSubscriptionId(subId);
        }
        if (sm == null && Build.VERSION.SDK_INT >= 31) sm = ctx.getSystemService(SmsManager.class);
        if (sm == null) sm = SmsManager.getDefault();

        ArrayList<String> parts = sm.divideMessage(body);
        if (parts.size() > 1) sm.sendMultipartTextMessage(address, null, parts, null, null);
        else sm.sendTextMessage(address, null, body, null, null);
    }

    /** وضعیت مجوزها برای نمایش در صفحهٔ وب */
    public static JSONObject perms(Context ctx) {
        return J.obj(
                "readSms", has(ctx, Manifest.permission.READ_SMS),
                "sendSms", has(ctx, Manifest.permission.SEND_SMS),
                "contacts", has(ctx, Manifest.permission.READ_CONTACTS),
                "phone", has(ctx, Manifest.permission.READ_PHONE_STATE),
                "notif", NotifListener.isEnabled(ctx));
    }
}
