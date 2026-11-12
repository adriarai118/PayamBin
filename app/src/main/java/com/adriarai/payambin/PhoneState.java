package com.adriarai.payambin;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.BatteryManager;
import android.provider.CallLog;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

/** وضعیت تماس جاری، فهرست تماس‌ها و باتری */
public final class PhoneState {
    private PhoneState() {}

    // تماس جاری
    private static volatile String callState = "idle"; // idle | ringing | offhook
    private static volatile String callNumber = "";
    private static volatile long callSince = 0;

    public static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent i) {
            String s = i.getStringExtra(TelephonyManager.EXTRA_STATE);
            String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            String ns;
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(s)) ns = "ringing";
            else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(s)) ns = "offhook";
            else ns = "idle";
            if (!ns.equals(callState)) {
                callState = ns;
                callSince = System.currentTimeMillis();
                if (ns.equals("ringing")) callNumber = "";
            }
            if (num != null && !num.isEmpty()) callNumber = num;
            NotifStore.Versions.calls.incrementAndGet();
        }
    };

    public static JSONObject call(Context ctx) {
        String num = callNumber;
        return J.obj("state", callState, "number", num,
                "name", num.isEmpty() ? null : SmsRepo.contactName(ctx, num),
                "since", callSince);
    }

    public static JSONArray calls(Context ctx, int limit) {
        JSONArray out = new JSONArray();
        if (!SmsRepo.has(ctx, Manifest.permission.READ_CALL_LOG)) return out;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null,
                    CallLog.Calls.DATE + " DESC");
            if (c == null) return out;
            int iId = c.getColumnIndex(CallLog.Calls._ID);
            int iNum = c.getColumnIndex(CallLog.Calls.NUMBER);
            int iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int iType = c.getColumnIndex(CallLog.Calls.TYPE);
            int iDate = c.getColumnIndex(CallLog.Calls.DATE);
            int iDur = c.getColumnIndex(CallLog.Calls.DURATION);
            while (c.moveToNext() && out.length() < limit) {
                String num = iNum >= 0 ? c.getString(iNum) : "";
                if (num == null) num = "";
                String name = iName >= 0 ? c.getString(iName) : null;
                if (name == null || name.isEmpty()) name = SmsRepo.contactName(ctx, num);
                out.put(J.obj(
                        "id", iId >= 0 ? c.getLong(iId) : 0,
                        "number", num,
                        "name", name,
                        // 1 ورودی، 2 خروجی، 3 بی‌پاسخ، 4 پیغام‌گیر، 5 ردشده، 6 مسدود
                        "type", iType >= 0 ? c.getInt(iType) : 0,
                        "date", iDate >= 0 ? c.getLong(iDate) : 0,
                        "duration", iDur >= 0 ? c.getLong(iDur) : 0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public static JSONObject battery(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return J.obj("level", level, "charging", bm.isCharging());
        } catch (Exception e) {
            return J.obj("level", -1, "charging", false);
        }
    }
}
