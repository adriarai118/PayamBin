package com.adriarai.payambin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** کنترل گوشی از کامپیوتر: لمس، کشیدن، اسکرول، دکمه‌های سیستم و تایپ */
public class ControlService extends AccessibilityService {
    public static volatile ControlService instance;

    public static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(c, ControlService.class).flattenToString();
        for (String part : s.split(":")) {
            if (part.equalsIgnoreCase(me)) return true;
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && cn.getPackageName().equals(c.getPackageName())) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Diag.init(this);
        Diag.log("کنترل از کامپیوتر فعال شد");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ---------------- ژست‌ها (x,y بر حسب پیکسل واقعی صفحه) ----------------

    private boolean gesture(Path path, long start, long duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, start, Math.max(1, duration)))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean tap(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 0, ms);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, 0, Math.max(50, Math.min(3000, ms)));
    }

    public boolean global(String name) {
        int a;
        switch (name) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return false;
                a = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "power": a = GLOBAL_ACTION_POWER_DIALOG; break;
            default: return false;
        }
        return performGlobalAction(a);
    }

    // ---------------- تایپ ----------------

    /** کادر فعال؛ همیشه با refresh تا متن و مکان‌نما تازه از خود برنامه خوانده شود (نه از حافظه‌ی موقت) */
    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (n == null) return null;
        n.refresh();
        return n.isEditable() ? n : null;
    }

    /** راه دوم: چسباندن از کلیپ‌بورد در جای مکان‌نما */
    private boolean paste(AccessibilityNodeInfo n, String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PayamBin", s));
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean setText(AccessibilityNodeInfo n, CharSequence text, int cursor) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (ok && cursor >= 0) {
            Bundle s = new Bundle();
            s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
            s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s);
        }
        return ok;
    }

    /** متن فعلی کادر (بدون متن راهنما) */
    private static String currentText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null) return "";
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        return t.toString();
    }

    /** نوشتن متن در جای مکان‌نما */
    public synchronized String type(String s) {
        AccessibilityNodeInfo n = focusedEditable();
        if (n == null) return "روی گوشی هیچ کادر متنی انتخاب نشده؛ اول روی کادر کلیک کنید";
        String cur = currentText(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || a > cur.length()) a = cur.length();
        if (b < a || b > cur.length()) b = a;
        if (b < a) { int t = a; a = b; b = t; }
        String next = cur.substring(0, a) + s + cur.substring(b);
        if (setText(n, next, a + s.length())) {
            // بررسی: آیا برنامه همان متن را قبول کرد؟
            n.refresh();
            String now = currentText(n);
            if (now.equals(next) || now.contains(s)) return null;
            // برنامه متن را تغییر داد/رد کرد → متن قبلی را برگردان و با چسباندن امتحان کن
            setText(n, cur, a);
            n.refresh();
        }
        if (paste(n, s)) return null;
        return "نوشتن در این کادر ممکن نشد";
    }

    public synchronized String key(String k) {
        AccessibilityNodeInfo n = focusedEditable();
        switch (k) {
            case "Backspace":
            case "Delete": {
                if (n == null) return null;
                String cur = currentText(n);
                int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
                if (a < 0 || a > cur.length()) a = cur.length();
                if (b < a || b > cur.length()) b = a;
                if (a != b) return setText(n, cur.substring(0, a) + cur.substring(b), a) ? null : "ناموفق";
                if (k.equals("Backspace")) {
                    if (a == 0) return null;
                    int cut = Character.isLowSurrogate(cur.charAt(a - 1)) && a >= 2 ? 2 : 1;
                    return setText(n, cur.substring(0, a - cut) + cur.substring(a), a - cut) ? null : "ناموفق";
                } else {
                    if (a >= cur.length()) return null;
                    return setText(n, cur.substring(0, a) + cur.substring(a + 1), a) ? null : "ناموفق";
                }
            }
            case "Enter":
                if (n != null && Build.VERSION.SDK_INT >= 30) {
                    if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return null;
                }
                return n != null ? type("\n") : null;
            case "SelectAll":
                if (n == null) return null;
                Bundle s = new Bundle();
                s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText(n).length());
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s);
                return null;
            case "ArrowLeft":
            case "ArrowRight": {
                if (n == null) return null;
                Bundle m = new Bundle();
                m.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
                m.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
                // در متن فارسی (راست‌به‌چپ) جهت‌ها برعکس است؛ ساده: چپ = عقب
                n.performAction(k.equals("ArrowLeft") ? AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                        : AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, m);
                return null;
            }
            default:
                return null;
        }
    }
}
