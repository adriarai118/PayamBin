package com.adriarai.payambin;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

/** اعلان‌های برنامه‌های دیگر (بله، تلگرام، واتساپ، ایتا، روبیکا ...) را ذخیره می‌کند */
public class NotifListener extends NotificationListenerService {
    public static volatile boolean connected = false;

    // دکمه‌ی «پاسخ» آخرین اعلان هر گفتگو (فقط در حافظه)
    private static final java.util.Map<String, Notification.Action> replies = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean canReply(String conv) {
        return conv != null && replies.containsKey(conv);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Action findReply(Notification n) {
        java.util.List<Notification.Action> all = new java.util.ArrayList<>();
        if (n.actions != null) all.addAll(java.util.Arrays.asList(n.actions));
        try {
            all.addAll(new Notification.WearableExtender(n).getActions());
        } catch (Exception ignored) {
        }
        for (Notification.Action a : all) {
            android.app.RemoteInput[] ri = a.getRemoteInputs();
            if (ri == null || a.actionIntent == null) continue;
            for (android.app.RemoteInput r : ri) if (r.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    /** ارسال پاسخ متنی از طریق دکمه‌ی «پاسخ» اعلان */
    public static String reply(Context ctx, String conv, String text) {
        Notification.Action a = conv == null ? null : replies.get(conv);
        if (a == null) return "برای این گفتگو دکمه‌ی پاسخ در دسترس نیست (اعلانش روی گوشی نیست)";
        try {
            android.app.RemoteInput[] rs = a.getRemoteInputs();
            Intent fill = new Intent();
            Bundle b = new Bundle();
            for (android.app.RemoteInput r : rs) b.putCharSequence(r.getResultKey(), text);
            android.app.RemoteInput.addResultsToIntent(rs, fill, b);
            a.actionIntent.send(ctx, 0, fill);
            Diag.log("پاسخ فرستاده شد به " + conv);
            return null;
        } catch (android.app.PendingIntent.CanceledException e) {
            replies.remove(conv);
            return "اعلان این گفتگو دیگر معتبر نیست؛ وقتی پیام تازه‌ای بیاید دوباره می‌شود پاسخ داد";
        } catch (Exception e) {
            return "ارسال نشد: " + e.getMessage();
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        Diag.init(this);
        Diag.log("دسترسی اعلان وصل شد");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        Diag.init(this);
        Diag.log("⚠️ سیستم دسترسی اعلان را قطع کرد — درخواست اتصال دوباره");
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(new ComponentName(this, NotifListener.class));
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        String me = new ComponentName(c, NotifListener.class).flattenToString();
        for (String part : s.split(":")) {
            if (part.equals(me)) return true;
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && cn.getPackageName().equals(c.getPackageName())) return true;
        }
        return false;
    }

    private static CharSequence cs(Bundle b, String key) {
        try {
            return b.getCharSequence(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean empty(CharSequence c) {
        return c == null || c.toString().trim().isEmpty();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName())) return;
            if (sbn.isOngoing()) return;
            Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            String cat = n.category;
            if (Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_TRANSPORT.equals(cat)
                    || Notification.CATEGORY_SERVICE.equals(cat)) return;

            // پیامک‌ها در زبانهٔ پیامک نمایش داده می‌شوند؛ اعلان برنامهٔ پیامک پیش‌فرض تکراری است
            String smsApp = Telephony.Sms.getDefaultSmsPackage(this);
            if (pkg.equals(smsApp)) return;

            Bundle ex = n.extras;
            if (ex == null) return;
            CharSequence title = cs(ex, Notification.EXTRA_TITLE);
            CharSequence conv = Build.VERSION.SDK_INT >= 24 ? cs(ex, Notification.EXTRA_CONVERSATION_TITLE) : null;
            CharSequence text = null, sender = null;

            // MessagingStyle: آخرین پیام گفتگو
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                Bundle last = (Bundle) msgs[msgs.length - 1];
                text = last.getCharSequence("text");
                sender = last.getCharSequence("sender");
            }
            if (empty(text)) text = cs(ex, Notification.EXTRA_BIG_TEXT);
            if (empty(text)) text = cs(ex, Notification.EXTRA_TEXT);
            if (empty(text)) {
                CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) text = lines[lines.length - 1];
            }
            if (empty(text) && empty(title)) return;

            String head = !empty(conv) ? conv.toString() : (title == null ? "" : title.toString());
            String convKey = pkg + "|" + head;
            Notification.Action ra = findReply(n);
            if (ra != null) replies.put(convKey, ra);
            JSONObject o = J.obj(
                    "pkg", pkg,
                    "app", appLabel(pkg),
                    "title", head,
                    "sender", (!empty(sender) && !sender.toString().equals(head)) ? sender.toString() : "",
                    "text", text == null ? "" : text.toString(),
                    "time", sbn.getPostTime(),
                    "conv", convKey);
            NotifStore.get(this).add(o);
            Diag.log("اعلان گرفته شد: " + o.optString("app"));
        } catch (Exception ignored) {
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }
}
