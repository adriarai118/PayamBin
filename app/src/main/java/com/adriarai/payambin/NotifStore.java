package com.adriarai.payambin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** نگهداری اعلان‌های دریافتی (پیام‌رسان‌ها و ...) در حافظه و فایل */
public final class NotifStore {
    private static final int MAX = 1000;
    private static NotifStore inst;

    public static synchronized NotifStore get(Context c) {
        if (inst == null) inst = new NotifStore(c.getApplicationContext());
        return inst;
    }

    private final ArrayList<JSONObject> items = new ArrayList<>(); // جدیدترین اول
    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long nextId = 1;
    private boolean savePending = false;

    private final File blockFile;
    private final ArrayList<JSONObject> rules = new ArrayList<>(); // {type: app|conv, key, pkg, app, title}

    private NotifStore(Context c) {
        file = new File(c.getFilesDir(), "notifs.json");
        blockFile = new File(c.getFilesDir(), "notif_block.json");
        load();
        loadRules();
    }

    // ---------------- مسدودسازی (تبلیغات) ----------------

    private void loadRules() {
        try {
            if (!blockFile.exists()) return;
            FileInputStream in = new FileInputStream(blockFile);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONArray a = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) rules.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    private void saveRules() {
        JSONArray a = new JSONArray();
        for (JSONObject o : rules) a.put(o);
        final String data = a.toString();
        io.execute(() -> {
            try {
                FileOutputStream out = new FileOutputStream(blockFile);
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Exception ignored) {
            }
        });
    }

    private boolean matches(JSONObject rule, String pkg, String title) {
        if (!rule.optString("pkg").equals(pkg)) return false;
        return rule.optString("type").equals("app") || rule.optString("title").equals(title);
    }

    private boolean blocked(String pkg, String title) {
        for (JSONObject r : rules) if (matches(r, pkg, title)) return true;
        return false;
    }

    /** مسدود کردن فرستنده (conv) یا کل برنامه (app) بر اساس یک اعلان؛ اعلان‌های قبلی مشابه هم حذف می‌شوند */
    public synchronized JSONObject block(long id, String scope) {
        JSONObject n = byId(id);
        if (n == null) return null;
        String pkg = n.optString("pkg"), title = n.optString("title");
        boolean app = "app".equals(scope);
        String key = app ? "app|" + pkg : "conv|" + pkg + "|" + title;
        for (JSONObject r : rules) if (r.optString("key").equals(key)) return r;
        JSONObject rule = J.obj("type", app ? "app" : "conv", "key", key, "pkg", pkg,
                "app", n.optString("app"), "title", app ? "" : title, "time", System.currentTimeMillis());
        rules.add(rule);
        saveRules();
        for (int i = items.size() - 1; i >= 0; i--) {
            JSONObject o = items.get(i);
            if (matches(rule, o.optString("pkg"), o.optString("title"))) items.remove(i);
        }
        Versions.notif.incrementAndGet();
        scheduleSave();
        return rule;
    }

    public synchronized void unblock(String key) {
        for (int i = rules.size() - 1; i >= 0; i--) if (rules.get(i).optString("key").equals(key)) rules.remove(i);
        saveRules();
        Versions.notif.incrementAndGet();
    }

    public synchronized JSONArray rules() {
        JSONArray a = new JSONArray();
        for (JSONObject o : rules) a.put(o);
        return a;
    }

    // ---------------- خوانده‌شده ----------------

    /** همهٔ اعلان‌های یک گفتگو خوانده شد؛ true اگر چیزی عوض شد */
    public synchronized boolean markReadConv(String conv) {
        boolean ch = false;
        for (JSONObject o : items) {
            if (!o.optBoolean("read") && conv.equals(o.optString("conv"))) { J.put(o, "read", true); ch = true; }
        }
        if (ch) { Versions.notif.incrementAndGet(); scheduleSave(); }
        return ch;
    }

    /**
     * علامت خوانده‌شده: با id (و همهٔ گفتگویش)، یا همهٔ یک برنامه (pkg)، یا همه.
     * فهرست گفتگوهایی که عوض شدند برمی‌گردد (برای خوانده‌کردن روی گوشی).
     */
    public synchronized java.util.Set<String> markRead(long id, String pkg, boolean all) {
        java.util.Set<String> convs = new java.util.LinkedHashSet<>();
        String conv = null;
        if (id > 0) {
            JSONObject n = byId(id);
            if (n == null) return convs;
            conv = n.optString("conv", "");
            if (conv.isEmpty()) {
                if (!n.optBoolean("read")) { J.put(n, "read", true); Versions.notif.incrementAndGet(); scheduleSave(); }
                return convs;
            }
        }
        boolean ch = false;
        for (JSONObject o : items) {
            if (o.optBoolean("read")) continue;
            if (conv != null) { if (!conv.equals(o.optString("conv"))) continue; }
            else if (!all && !(pkg != null && pkg.equals(o.optString("pkg")))) continue;
            J.put(o, "read", true);
            ch = true;
            String c = o.optString("conv", "");
            if (!c.isEmpty()) convs.add(c);
        }
        if (conv != null) convs.add(conv);
        if (ch) { Versions.notif.incrementAndGet(); scheduleSave(); }
        return convs;
    }

    public synchronized boolean delete(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).optLong("id") == id) {
                items.remove(i);
                Versions.notif.incrementAndGet();
                scheduleSave();
                return true;
            }
        }
        return false;
    }

    private void load() {
        try {
            if (!file.exists()) return;
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONArray a = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!o.has("read")) J.put(o, "read", true); // اعلان‌های نسخه‌های قبل
                items.add(o);
                nextId = Math.max(nextId, o.optLong("id") + 1);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void add(JSONObject o) {
        String pkg = o.optString("pkg"), title = o.optString("title"), text = o.optString("text");
        if (!o.optBoolean("out") && blocked(pkg, title)) return; // مسدودشده (تبلیغات)
        // تکراری‌ها (به‌روزرسانی همان اعلان) را نادیده بگیر
        for (int i = 0; i < Math.min(40, items.size()); i++) {
            JSONObject p = items.get(i);
            if (p.optString("pkg").equals(pkg) && p.optString("title").equals(title)
                    && p.optString("text").equals(text)) return;
        }
        J.put(o, "id", nextId++);
        J.put(o, "read", o.optBoolean("out"));
        items.add(0, o);
        while (items.size() > MAX) items.remove(items.size() - 1);
        Versions.notif.incrementAndGet();
        scheduleSave();
    }

    public synchronized JSONArray list(String pkg, int limit) {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) {
            if (pkg != null && !pkg.isEmpty() && !pkg.equals(o.optString("pkg"))) continue;
            a.put(o);
            if (a.length() >= limit) break;
        }
        return a;
    }

    public synchronized JSONObject byId(long id) {
        for (JSONObject o : items) if (o.optLong("id") == id) return o;
        return null;
    }

    /** اعلان‌های جدیدتر از زمان داده‌شده (جدیدترین اول) */
    public synchronized JSONArray since(long time, int limit) {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) {
            if (o.optLong("time") <= time) break;
            a.put(o);
            if (a.length() >= limit) break;
        }
        return a;
    }

    public synchronized JSONArray apps() {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (JSONObject o : items) {
            String pkg = o.optString("pkg");
            JSONObject a = m.get(pkg);
            if (a == null) {
                a = J.obj("pkg", pkg, "app", o.optString("app"), "count", 0, "unread", 0, "last", o.optLong("time"));
                m.put(pkg, a);
            }
            J.put(a, "count", a.optInt("count") + 1);
            if (!o.optBoolean("read")) J.put(a, "unread", a.optInt("unread") + 1);
        }
        JSONArray out = new JSONArray();
        for (JSONObject a : m.values()) out.put(a);
        return out;
    }

    public synchronized void clear(String pkg) {
        if (pkg == null || pkg.isEmpty()) items.clear();
        else {
            for (int i = items.size() - 1; i >= 0; i--)
                if (pkg.equals(items.get(i).optString("pkg"))) items.remove(i);
        }
        Versions.notif.incrementAndGet();
        scheduleSave();
    }

    private void scheduleSave() {
        if (savePending) return;
        savePending = true;
        io.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            String data;
            synchronized (NotifStore.this) {
                savePending = false;
                JSONArray a = new JSONArray();
                for (JSONObject o : items) a.put(o);
                data = a.toString();
            }
            try {
                File tmp = new File(file.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            } catch (Exception ignored) {
            }
        });
    }

    /** شمارنده‌های تغییر برای اطلاع صفحهٔ وب از پیام جدید */
    public static final class Versions {
        public static final AtomicLong sms = new AtomicLong(System.currentTimeMillis());
        public static final AtomicLong notif = new AtomicLong(System.currentTimeMillis());
        public static final AtomicLong calls = new AtomicLong(System.currentTimeMillis());
        public static final AtomicLong files = new AtomicLong(System.currentTimeMillis());
        public static final AtomicLong clip = new AtomicLong(System.currentTimeMillis());

        private Versions() {}
    }
}
