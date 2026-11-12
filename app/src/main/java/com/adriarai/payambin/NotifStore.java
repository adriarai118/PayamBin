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

    private NotifStore(Context c) {
        file = new File(c.getFilesDir(), "notifs.json");
        load();
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
                items.add(o);
                nextId = Math.max(nextId, o.optLong("id") + 1);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void add(JSONObject o) {
        String pkg = o.optString("pkg"), title = o.optString("title"), text = o.optString("text");
        // تکراری‌ها (به‌روزرسانی همان اعلان) را نادیده بگیر
        for (int i = 0; i < Math.min(40, items.size()); i++) {
            JSONObject p = items.get(i);
            if (p.optString("pkg").equals(pkg) && p.optString("title").equals(title)
                    && p.optString("text").equals(text)) return;
        }
        J.put(o, "id", nextId++);
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

    public synchronized JSONArray apps() {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (JSONObject o : items) {
            String pkg = o.optString("pkg");
            JSONObject a = m.get(pkg);
            if (a == null) {
                a = J.obj("pkg", pkg, "app", o.optString("app"), "count", 0, "last", o.optLong("time"));
                m.put(pkg, a);
            }
            J.put(a, "count", a.optInt("count") + 1);
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

        private Versions() {}
    }
}
