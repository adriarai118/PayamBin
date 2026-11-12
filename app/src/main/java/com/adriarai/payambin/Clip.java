package com.adriarai.payambin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** کلیپ‌بورد مشترک (فقط در حافظه) */
public final class Clip {
    private Clip() {}

    private static final ArrayList<JSONObject> items = new ArrayList<>();
    private static long nextId = 1;

    public static synchronized void add(String text, String from) {
        if (text == null || text.isEmpty()) return;
        if (text.length() > 100_000) text = text.substring(0, 100_000);
        if (!items.isEmpty() && items.get(0).optString("text").equals(text)) return;
        items.add(0, J.obj("id", nextId++, "text", text, "from", from, "time", System.currentTimeMillis()));
        while (items.size() > 30) items.remove(items.size() - 1);
        NotifStore.Versions.clip.incrementAndGet();
    }

    public static synchronized JSONArray list() {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        return a;
    }

    /** متن کامپیوتر را در کلیپ‌بورد گوشی بگذار */
    public static void setPhone(Context ctx, String text) {
        add(text, "pc");
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("PayamBin", text));
            } catch (Exception e) {
                Diag.log("کلیپ‌بورد گوشی تنظیم نشد: " + e.getMessage());
            }
        });
    }
}
