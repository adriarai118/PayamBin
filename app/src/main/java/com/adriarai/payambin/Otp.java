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

    /** کلمه‌هایی که یعنی «عدد بعدی کد است» */
    private static final String OTP_KW =
            "کد|رمز|پویا|تایید|تأیید|password|passcode|code|otp|pass|pin|verification";
    /** کلمه‌هایی که یعنی «عدد بعدی قطعاً کد نیست» (مبلغ، حساب، تاریخ، کد پیگیری…) */
    private static final String HARD_KW =
            "مبلغ|مانده|موجودی|ریال|تومان|حساب|شماره|تاریخ|ساعت|پیگیری|رهگیری|سفارش|مرسوله|بارکد|"
            + "amount|balance|account|date|time|ref|tracking";
    /** کلمه‌های خنثی که در عبارت‌هایی مثل «رمز خرید» یا «رمز انتقال» می‌آیند */
    private static final String SOFT_KW = "خرید|انتقال|برداشت|واریز|کارت|card|دوم|اینترنتی|یکبار|مصرف";
    private static final Pattern KW = Pattern.compile("(" + OTP_KW + ")|(" + HARD_KW + ")|(" + SOFT_KW + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUM = Pattern.compile("(?<![\\d,./:٫٬*\\-])(\\d{4,8})(?![\\d,/:٫٬*]|\\.\\d)");
    /** عدد چسبیده به ساعت مثل 0823-14:08 یا 14:08 0823 تاریخ است */
    private static final Pattern TIME_AFTER = Pattern.compile("^\\s*[-_]?\\s*\\d{1,2}:\\d{2}");
    private static final Pattern TIME_BEFORE = Pattern.compile("\\d{1,2}:\\d{2}\\s*[-_]?\\s*$");
    private static final Pattern UNIT_AFTER = Pattern.compile("^\\s*(ریال|تومان|rial|irr)", Pattern.CASE_INSENSITIVE);
    private static final int NEAR = 30, CHAIN_GAP = 12;

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

    /** یکسان‌سازی: حروف عربی→فارسی، حذف نویسه‌های نامرئی */
    static String norm(String s) {
        String t = java.text.Normalizer.normalize(latinDigits(s), java.text.Normalizer.Form.NFKC);
        t = latinDigits(t).replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک').replace('ة', 'ه');
        return t.replaceAll("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff\\u0640]", "");
    }

    /**
     * کد را از متن بیرون می‌کشد؛ اگر مطمئن نبود null برمی‌گرداند.
     * عدد فقط وقتی کد است که عبارت درست قبلش «کد/رمز/…» داشته باشد و «مبلغ/مانده/حساب/تاریخ/…» نداشته باشد،
     * یا کمی بعدش «کد/رمز» آمده باشد. بدون هیچ کلمهٔ کد/رمز چیزی برنمی‌گرداند.
     */
    public static String extract(String text) {
        String t = norm(text);
        List<int[]> kws = new ArrayList<>(); // {start, end, type: 1=otp 2=hard 3=soft}
        Matcher k = KW.matcher(t);
        while (k.find()) kws.add(new int[]{k.start(), k.end(), k.group(1) != null ? 1 : k.group(2) != null ? 2 : 3});
        boolean anyOtp = false;
        for (int[] kw : kws) if (kw[2] == 1) anyOtp = true;
        if (!anyOtp) return null;

        String best = null;
        int bestScore = Integer.MAX_VALUE;
        Matcher m = NUM.matcher(t);
        while (m.find()) {
            int s = m.start(1), e = m.end(1);
            String after = t.substring(e), before = t.substring(0, s);
            if (TIME_AFTER.matcher(after).find() || TIME_BEFORE.matcher(before).find()) continue;
            if (UNIT_AFTER.matcher(after).find()) continue;

            // زنجیرهٔ کلمه‌های پشت سر هم درست قبل از عدد، مثل «رمز دوم خرید :»
            int idx = -1;
            for (int i = 0; i < kws.size(); i++) if (kws.get(i)[1] <= s) idx = i;
            boolean otp = false, hard = false;
            int gapToNum = -1;
            if (idx >= 0 && s - kws.get(idx)[1] <= NEAR && !hasNum(t, kws.get(idx)[1], s)) {
                gapToNum = s - kws.get(idx)[1];
                int edge = kws.get(idx)[0];
                for (int i = idx; i >= 0; i--) {
                    int[] kw = kws.get(i);
                    if (i < idx && (edge - kw[1] > CHAIN_GAP || hasNum(t, kw[1], edge))) break;
                    if (kw[2] == 1) otp = true; else if (kw[2] == 2) hard = true;
                    edge = kw[0];
                }
            }
            if (hard) continue;
            int score = -1;
            if (otp) score = gapToNum;
            else {
                for (int[] kw : kws) {
                    if (kw[0] < e) continue;
                    if (kw[0] - e > NEAR || hasNum(t, e, kw[0])) break;
                    if (kw[2] == 1) { score = 100 + kw[0] - e; break; }
                    if (kw[2] == 2) break;
                }
            }
            if (score >= 0 && score < bestScore) { bestScore = score; best = m.group(1); }
        }
        return best;
    }

    private static boolean hasNum(String t, int from, int to) {
        for (int i = from; i < to; i++) if (Character.isDigit(t.charAt(i))) return true;
        return false;
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
