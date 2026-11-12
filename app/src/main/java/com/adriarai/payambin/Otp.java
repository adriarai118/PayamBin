package com.adriarai.payambin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** پیدا کردن آخرین رمز پویا / کد تأیید از پیامک‌ها و اعلان‌ها */
public final class Otp {
    private Otp() {}

    private static final Pattern KW = Pattern.compile(
            "(کد|كد|رمز|پویا|پويا|تایید|تأیید|تاييد|code|otp|pass|pin|verification)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM = Pattern.compile("(?<![\\d,./:٫٬-])(\\d{4,8})(?![\\d,/:٫٬]|\\.\\d)");

    public static String latinDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '۰' && ch <= '۹') sb.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') sb.append((char) ('0' + (ch - '٠')));
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** کد را از متن بیرون می‌کشد؛ اگر مطمئن نبود null برمی‌گرداند */
    public static String extract(String text) {
        String t = latinDigits(text);
        List<String> nums = new ArrayList<>();
        List<Integer> pos = new ArrayList<>();
        Matcher m = NUM.matcher(t);
        while (m.find()) {
            nums.add(m.group(1));
            pos.add(m.start(1));
        }
        if (nums.isEmpty()) return null;
        Matcher k = KW.matcher(t);
        if (k.find()) {
            int kp = k.start();
            for (int i = 0; i < nums.size(); i++) if (pos.get(i) >= kp) return nums.get(i);
            return nums.get(nums.size() - 1); // کد قبل از کلمهٔ کلیدی آمده
        }
        return nums.size() == 1 ? nums.get(0) : null;
    }

    /** آخرین کد در بازهٔ maxAgeMs و جدیدتر از since */
    public static JSONObject latest(Context ctx, long maxAgeMs, long since) {
        long from = Math.max(System.currentTimeMillis() - maxAgeMs, since);
        JSONObject best = null;

        JSONArray sms = SmsRepo.recentInbox(ctx, from, 15);
        for (int i = 0; i < sms.length(); i++) {
            JSONObject m = sms.optJSONObject(i);
            String code = extract(m.optString("body"));
            if (code != null) {
                String name = SmsRepo.contactName(ctx, m.optString("address"));
                best = J.obj("code", code, "source", "sms",
                        "from", name != null ? name : m.optString("address"),
                        "text", m.optString("body"), "time", m.optLong("date"));
                break;
            }
        }

        JSONArray ns = NotifStore.get(ctx).since(from, 30);
        for (int i = 0; i < ns.length(); i++) {
            JSONObject n = ns.optJSONObject(i);
            String code = extract(n.optString("title") + " " + n.optString("text"));
            if (code != null) {
                if (best == null || n.optLong("time") > best.optLong("time")) {
                    best = J.obj("code", code, "source", n.optString("app"),
                            "from", n.optString("title"),
                            "text", n.optString("text"), "time", n.optLong("time"));
                }
                break;
            }
        }
        return best != null ? best : J.obj("code", null);
    }
}
