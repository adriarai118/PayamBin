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
    private static volatile NotifListener inst;

    // دکمه‌ی «خوانده شد» و کلیدهای اعلان‌های هر گفتگو روی گوشی (فقط در حافظه)
    private static final java.util.Map<String, Notification.Action> markActions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> keyConv = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> selfCancel = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.regex.Pattern MARK_RE = java.util.regex.Pattern.compile(
            "\\bmark(ed)? (as )?read\\b|^read$|خوانده|خواندم|دیده شد|прочитано", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static boolean canMark(String conv) {
        return conv != null && markActions.containsKey(conv);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Action findMarkRead(Notification n) {
        java.util.List<Notification.Action> all = new java.util.ArrayList<>();
        if (n.actions != null) all.addAll(java.util.Arrays.asList(n.actions));
        try {
            all.addAll(new Notification.WearableExtender(n).getActions());
        } catch (Exception ignored) {
        }
        Notification.Action byTitle = null;
        for (Notification.Action a : all) {
            if (a == null || a.actionIntent == null) continue;
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
            if (Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return a;
            String t = a.title == null ? "" : a.title.toString().trim();
            if (byTitle == null && t.length() <= 30 && MARK_RE.matcher(t).find()) byTitle = a;
        }
        return byTitle;
    }

    /**
     * گفتگو روی گوشی «خوانده شد»: اگر برنامه دکمه‌ی «خوانده شد» دارد آن را می‌زند (پیام در خود پیام‌رسان خوانده‌شده می‌شود)
     * و اعلان‌های آن گفتگو را از نوار اعلان گوشی برمی‌دارد.
     */
    public static void markRead(Context ctx, String conv) {
        if (conv == null || conv.isEmpty()) return;
        Notification.Action a = markActions.remove(conv);
        if (a != null) {
            try {
                a.actionIntent.send(ctx, 0, new Intent());
                Diag.log("«خوانده شد» زده شد: " + conv);
            } catch (Exception ignored) {
            }
        }
        NotifListener l = inst;
        if (l == null) return;
        for (java.util.Map.Entry<String, String> e : new java.util.ArrayList<>(keyConv.entrySet())) {
            if (!conv.equals(e.getValue())) continue;
            selfCancel.add(e.getKey());
            keyConv.remove(e.getKey());
            try {
                l.cancelNotification(e.getKey());
            } catch (Exception ignored) {
            }
        }
    }

    /** اعلان از روی گوشی برداشته شد (کاربر روی گوشی بازش کرد/کنار زد یا خود برنامه پاکش کرد چون پیام خوانده شد) */
    private void removed(StatusBarNotification sbn, int reason) {
        try {
            String key = sbn.getKey();
            String conv = keyConv.remove(key);
            if (selfCancel.remove(key)) return;
            if (conv == null) return;
            if (Build.VERSION.SDK_INT >= 26 && reason == REASON_LISTENER_CANCEL) return;
            if (keyConv.containsValue(conv)) return; // هنوز اعلان دیگری از همین گفتگو روی گوشی هست
            markActions.remove(conv);
            if (NotifStore.get(this).markReadConv(conv)) Diag.log("روی گوشی خوانده شد: " + conv);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        removed(sbn, reason);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        if (Build.VERSION.SDK_INT < 26) removed(sbn, 0);
    }

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
        inst = this;
        Diag.init(this);
        Diag.log("دسترسی اعلان وصل شد");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        inst = null;
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
            Notification.Action ma = findMarkRead(n);
            if (ma != null) markActions.put(convKey, ma);
            keyConv.put(sbn.getKey(), convKey);
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
