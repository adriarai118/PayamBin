package com.adriarai.payambin;

import org.json.JSONException;
import org.json.JSONObject;

/** کمک برای ساخت JSON بدون try/catch در همه‌جا */
public final class J {
    private J() {}

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    public static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
