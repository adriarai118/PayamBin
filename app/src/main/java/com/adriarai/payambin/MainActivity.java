package com.adriarai.payambin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static volatile boolean visible = false;

    // ---------------- تم ----------------
    private boolean dark;
    private int BG, CARD, TEXT, MUTED, LINE, ACCENT, ON_ACCENT, ACCENT_SOFT, OK, BAD, BAR;

    private void loadTheme() {
        String t = Prefs.theme(this);
        if (t.equals("dark")) dark = true;
        else if (t.equals("light")) dark = false;
        else dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BG = c("#0B1220"); CARD = c("#111A2B"); TEXT = c("#E5EDF7"); MUTED = c("#8A9AB3");
            LINE = c("#1F2A3D"); ACCENT = c("#2DD4BF"); ON_ACCENT = c("#042F2B"); ACCENT_SOFT = c("#0F3B38");
            OK = c("#4ADE80"); BAD = c("#F87171"); BAR = c("#0E1626");
        } else {
            BG = c("#F3F5F7"); CARD = c("#FFFFFF"); TEXT = c("#0F172A"); MUTED = c("#64748B");
            LINE = c("#E3E7EC"); ACCENT = c("#0F766E"); ON_ACCENT = Color.WHITE; ACCENT_SOFT = c("#E6F6F3");
            OK = c("#15803D"); BAD = c("#B91C1C"); BAR = Color.WHITE;
        }
    }

    private static int c(String hex) {
        return Color.parseColor(hex);
    }

    @SuppressWarnings("deprecation")
    private void styleWindow() {
        getWindow().setStatusBarColor(BAR);
        getWindow().setNavigationBarColor(BG);
        int flags = 0;
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    // ---------------- ساختار صفحه ----------------
    private FrameLayout frame;
    private LinearLayout content, drawer, drawerItems;
    private ScrollView scroll;
    private View dim;
    private TextView barTitle, drawerStatus;
    private boolean drawerOpen = false;
    private String page = "home";
    private Runnable pageRefresh;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pageRefresh != null) pageRefresh.run();
            updateDrawerStatus();
            handler.postDelayed(this, 2000);
        }
    };

    private static final String[][] PAGES = {
            {"home", "🏠", "خانه"},
            {"perms", "🔐", "مجوزها"},
            {"settings", "⚙️", "تنظیمات"},
            {"update", "⬇️", "آپدیت"},
            {"diag", "🩺", "گزارش عیب‌یابی"},
            {"about", "ℹ️", "درباره برنامه"},
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(this);
        ScreenService.restoreBrightnessIfNeeded(this);
        loadTheme();
        styleWindow();
        if (savedInstanceState != null) page = savedInstanceState.getString("page", "home");
        build();
        // بار اول: سرور را روشن کن
        if (savedInstanceState == null && !ServerService.running && ServerService.lastError == null)
            ServerService.start(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page);
    }

    private void build() {
        frame = new FrameLayout(this);
        frame.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        frame.setBackgroundColor(BG);

        // --- محتوای اصلی: نوار بالا + صفحه ---
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        frame.addView(main, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(6), 0, dp(6), 0);
        bar.setElevation(dp(2));
        main.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView burger = new TextView(this);
        burger.setText("☰");
        burger.setTextSize(24);
        burger.setTextColor(TEXT);
        burger.setGravity(Gravity.CENTER);
        burger.setBackground(ripple(Color.TRANSPARENT, 0, dp(24)));
        burger.setOnClickListener(v -> toggleDrawer(true));
        burger.setContentDescription("منو");
        bar.addView(burger, new LinearLayout.LayoutParams(dp(48), dp(48)));

        barTitle = new TextView(this);
        barTitle.setTextSize(19);
        barTitle.setTypeface(Typeface.DEFAULT_BOLD);
        barTitle.setTextColor(TEXT);
        barTitle.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, -2, 1));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(content);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- پرده‌ی تیره پشت منو ---
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        dim.setOnClickListener(v -> toggleDrawer(false));
        frame.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // --- منوی کشویی (از سمت راست) ---
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(CARD);
        drawer.setElevation(dp(16));
        drawer.setClickable(true);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(290), -1);
        dlp.gravity = Gravity.RIGHT;
        frame.addView(drawer, dlp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(28), dp(20), dp(18));
        head.setBackgroundColor(ACCENT_SOFT);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView appName = text(20, TEXT, true);
        appName.setText("پیام‌بین");
        appName.setPadding(0, dp(10), 0, 0);
        head.addView(appName);
        drawerStatus = text(13, MUTED, false);
        head.addView(drawerStatus);
        drawer.addView(head);

        drawerItems = new LinearLayout(this);
        drawerItems.setOrientation(LinearLayout.VERTICAL);
        drawerItems.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView ds = new ScrollView(this);
        ds.addView(drawerItems);
        drawer.addView(ds, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView ver = text(12, MUTED, false);
        ver.setText("نسخه‌ی " + Updater.currentVersion(this));
        ver.setPadding(dp(20), dp(8), dp(20), dp(16));
        drawer.addView(ver);

        setContentView(frame);
        drawer.setTranslationX(dp(330));
        drawer.post(() -> drawer.setTranslationX(drawer.getWidth() + dp(20)));
        showPage(page);
    }

    private void renderDrawerItems() {
        drawerItems.removeAllViews();
        for (String[] p : PAGES) {
            boolean sel = p[0].equals(page);
            TextView it = new TextView(this);
            it.setText(p[1] + "   " + p[2]);
            it.setTextSize(15);
            it.setTextColor(sel ? ACCENT : TEXT);
            if (sel) it.setTypeface(Typeface.DEFAULT_BOLD);
            it.setPadding(dp(16), dp(13), dp(16), dp(13));
            it.setBackground(ripple(sel ? ACCENT_SOFT : Color.TRANSPARENT, 0, dp(12)));
            final String id = p[0];
            it.setOnClickListener(v -> {
                toggleDrawer(false);
                showPage(id);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(2);
            drawerItems.addView(it, lp);
        }
    }

    private void toggleDrawer(boolean open) {
        drawerOpen = open;
        if (open) {
            dim.setVisibility(View.VISIBLE);
            dim.animate().alpha(0.45f).setDuration(200).start();
            drawer.animate().translationX(0).setDuration(220).start();
        } else {
            dim.animate().alpha(0f).setDuration(200).withEndAction(() -> dim.setVisibility(View.GONE)).start();
            drawer.animate().translationX(drawer.getWidth() + dp(20)).setDuration(220).start();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawerOpen) toggleDrawer(false);
        else if (!page.equals("home")) showPage("home");
        else super.onBackPressed();
    }

    private void updateDrawerStatus() {
        if (drawerStatus == null) return;
        drawerStatus.setText(ServerService.running ? "● سرور روشن است" : "● سرور خاموش است");
        drawerStatus.setTextColor(ServerService.running ? OK : BAD);
    }

    private void showPage(String id) {
        page = id;
        content.removeAllViews();
        pageRefresh = null;
        for (String[] p : PAGES) if (p[0].equals(id)) barTitle.setText(id.equals("home") ? "پیام‌بین" : p[2]);
        switch (id) {
            case "perms": pagePerms(); break;
            case "settings": pageSettings(); break;
            case "update": pageUpdate(); break;
            case "diag": pageDiag(); break;
            case "about": pageAbout(); break;
            default: pageHome();
        }
        renderDrawerItems();
        updateDrawerStatus();
        scroll.scrollTo(0, 0);
        if (pageRefresh != null) pageRefresh.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        handler.post(tick);
        CameraService.ensureStandby(this); // برنامه جلوی چشم است → دوربین بعداً بدون تأیید روشن می‌شود
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        handler.removeCallbacks(tick);
    }

    // ---------------- اجزای ظاهری ----------------
    private RippleDrawable ripple(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        int rc = dark ? Color.argb(50, 255, 255, 255) : Color.argb(30, 0, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rc), g, mask);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        g.setCornerRadius(dp(16));
        l.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(l, lp);
        return l;
    }

    private TextView heading(LinearLayout parent, String s) {
        TextView t = text(16, TEXT, true);
        t.setText(s);
        t.setPadding(0, 0, 0, dp(6));
        parent.addView(t);
        return t;
    }

    private TextView para(LinearLayout parent, String s) {
        TextView t = text(14, MUTED, false);
        t.setText(s);
        parent.addView(t);
        return t;
    }

    private TextView button(LinearLayout parent, String label, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setBackground(ripple(primary ? ACCENT : CARD, primary ? 0 : LINE, dp(12)));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    /** ردیف وضعیت: ✓/✗ + عنوان + دکمه‌ی کوچک */
    private TextView[] statusRow(LinearLayout parent, String label, String action, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView mark = text(16, OK, true);
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(dp(28), -2));
        TextView t = text(14, TEXT, false);
        t.setText(label);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView b = new TextView(this);
        b.setText(action);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        b.setBackground(ripple(ACCENT_SOFT, 0, dp(10)));
        b.setOnClickListener(l);
        row.addView(b);
        parent.addView(row);
        return new TextView[]{mark, b};
    }

    private void setMark(TextView[] r, boolean ok) {
        r[0].setText(ok ? "✓" : "✗");
        r[0].setTextColor(ok ? OK : BAD);
        r[1].setVisibility(ok ? View.INVISIBLE : View.VISIBLE);
    }

    private Switch switchRow(LinearLayout parent, String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(14);
        s.setTextColor(TEXT);
        s.setChecked(checked);
        s.setPadding(0, dp(6), 0, dp(6));
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, dark ? c("#94A3B8") : c("#F1F5F9")}));
        s.setTrackTintList(new ColorStateList(st, new int[]{ACCENT_SOFT, dark ? c("#334155") : c("#CBD5E1")}));
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    // ---------------- صفحه‌ها ----------------
    private void pageHome() {
        LinearLayout k = card();
        final TextView status = text(15, OK, true);
        k.addView(status);
        final TextView address = text(22, ACCENT, true);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.CENTER);
        address.setPadding(0, dp(12), 0, dp(4));
        address.setOnClickListener(v -> copyAddress(address));
        k.addView(address);
        TextView hint = text(12, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setText("یکی از این آدرس‌ها را در مرورگر کامپیوتر (همان شبکه) باز کنید — برای کپی لمس کنید");
        k.addView(hint);
        final TextView pin = text(20, TEXT, true);
        pin.setGravity(Gravity.CENTER);
        pin.setPadding(0, dp(14), 0, dp(4));
        k.addView(pin);
        final TextView toggle = button(k, "", true, v -> {
            if (ServerService.running) ServerService.stop(this);
            else {
                ServerService.lastError = null;
                ServerService.start(this);
            }
            handler.postDelayed(() -> { if (pageRefresh != null) pageRefresh.run(); }, 400);
        });

        LinearLayout kc = card();
        heading(kc, "کلیپ‌بورد");
        para(kc, "متنی که در گوشی کپی کرده‌اید را برای کامپیوتر بفرستید. (برای متن یا فایل از هر برنامه‌ای هم می‌توانید «اشتراک‌گذاری ← ارسال به کامپیوتر» را بزنید.)");
        button(kc, "📋 ارسال کلیپ‌بورد به کامپیوتر", false, v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                CharSequence t = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(this) : null;
                if (t == null || t.length() == 0) {
                    Toast.makeText(this, "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show();
                    return;
                }
                Clip.add(t.toString(), "phone");
                Toast.makeText(this, "برای کامپیوتر فرستاده شد", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "خواندن کلیپ‌بورد ممکن نشد", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout k2 = card();
        heading(k2, "وضعیت");
        final TextView summary = text(14, TEXT, false);
        k2.addView(summary);
        final TextView fix = button(k2, "رفتن به مجوزها", false, v -> showPage("perms"));

        pageRefresh = () -> {
            boolean run = ServerService.running;
            String ip = ServerService.address(this);
            if (run) {
                status.setText("● سرور روشن است");
                status.setTextColor(OK);
                address.setText(ip == null ? "به وای‌فای وصل نیستید"
                        : "http://" + Mdns.HOST + ":" + Prefs.port(this) + "\n" + ip);
                toggle.setText("خاموش کردن سرور");
            } else {
                String err = ServerService.lastError;
                status.setText(err != null ? "● " + err : "● سرور خاموش است");
                status.setTextColor(BAD);
                address.setText(ip == null ? "—" : ip);
                toggle.setText("روشن کردن سرور");
            }
            pin.setText("رمز ورود: " + Prefs.pin(this));
            int missing = missingCount();
            summary.setText(missing == 0 ? "✓ همه‌چیز آماده است" : "⚠️ " + missing + " مورد نیاز به توجه دارد");
            summary.setTextColor(missing == 0 ? OK : BAD);
            fix.setVisibility(missing == 0 ? View.GONE : View.VISIBLE);
        };
    }

    private int missingCount() {
        int n = 0;
        if (!SmsRepo.has(this, Manifest.permission.READ_SMS)) n++;
        if (!SmsRepo.has(this, Manifest.permission.SEND_SMS)) n++;
        if (!SmsRepo.has(this, Manifest.permission.READ_CONTACTS)) n++;
        if (!SmsRepo.has(this, Manifest.permission.READ_CALL_LOG)) n++;
        if (!NotifListener.isEnabled(this)) n++;
        if (!SmsRepo.ignoringBattery(this)) n++;
        return n;
    }

    private void pagePerms() {
        LinearLayout k = card();
        heading(k, "دسترسی‌های اصلی");
        final TextView[] sms = statusRow(k, "خواندن پیامک", "اجازه", v -> askPerms());
        final TextView[] send = statusRow(k, "ارسال پیامک از کامپیوتر", "اجازه", v -> askPerms());
        final TextView[] cont = statusRow(k, "نمایش نام مخاطبین", "اجازه", v -> askPerms());
        final TextView[] calls = statusRow(k, "نمایش تماس‌ها", "اجازه", v -> askPerms());
        final TextView[] media = statusRow(k, "عکس‌ها و ویدیوها (برای کامپیوتر)", "اجازه", v -> askPerms());
        final TextView[] cam = statusRow(k, "دوربین (پخش زنده برای کامپیوتر)", "اجازه", v -> askPerms());
        final TextView[] mic = statusRow(k, "میکروفون (صدای زنده و ضبط با صدا)", "اجازه", v -> askPerms());
        final TextView[] dimRow = statusRow(k, "کم کردن نور گوشی هنگام تماشای صفحه از کامپیوتر", "اجازه", v -> {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    openAppDetails();
                }
            }
        });
        final TextView[] ctl = statusRow(k, "کنترل گوشی با ماوس و کیبورد (دسترسی‌پذیری)", "فعال‌سازی", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "«پیام‌بین — کنترل از کامپیوتر» را روشن کنید", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                openAppDetails();
            }
        });
        final TextView[] notif = statusRow(k, "اعلان پیام‌رسان‌ها (بله، تلگرام، …)", "فعال‌سازی", v -> {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Exception e) {
                openAppDetails();
            }
        });

        LinearLayout k2 = card();
        heading(k2, "روشن ماندن در پس‌زمینه");
        final TextView[] bat = statusRow(k2, "معافیت از بهینه‌سازی باتری", "فعال‌سازی", v -> askBattery());
        final TextView[] inst = statusRow(k2, "اجازه‌ی نصب آپدیت‌ها", "فعال‌سازی", v -> {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            }
        });
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            para(k2, "\nگوشی شیائومی: Autostart را روشن و صرفه‌جویی باتری را روی «No restrictions» بگذارید.");
            button(k2, "Autostart شیائومی", false, v -> openXiaomiAutostart());
            button(k2, "صرفه‌جویی باتری ← No restrictions", false, v -> openAppDetails());
        }

        LinearLayout k3 = card();
        heading(k3, "اگر گزینه‌ای خاکستری بود");
        para(k3, "تنظیمات گوشی ← برنامه‌ها ← پیام‌بین ← منوی سه‌نقطه ← «Allow restricted settings»، سپس دوباره امتحان کنید.");
        button(k3, "باز کردن تنظیمات برنامه", false, v -> openAppDetails());

        pageRefresh = () -> {
            setMark(sms, SmsRepo.has(this, Manifest.permission.READ_SMS));
            setMark(send, SmsRepo.has(this, Manifest.permission.SEND_SMS));
            setMark(cont, SmsRepo.has(this, Manifest.permission.READ_CONTACTS));
            setMark(calls, SmsRepo.has(this, Manifest.permission.READ_CALL_LOG));
            setMark(media, MediaRepo.allowed(this));
            setMark(cam, SmsRepo.has(this, Manifest.permission.CAMERA));
            setMark(mic, SmsRepo.has(this, Manifest.permission.RECORD_AUDIO));
            setMark(ctl, ControlService.isEnabled(this));
            setMark(dimRow, ScreenService.canDim(this));
            setMark(notif, NotifListener.isEnabled(this));
            setMark(bat, SmsRepo.ignoringBattery(this));
            setMark(inst, Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls());
        };
    }

    private void pageSettings() {
        LinearLayout k = card();
        heading(k, "تم");
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String cur = Prefs.theme(this);
        String[][] opts = {{"system", "خودکار"}, {"light", "☀️ روشن"}, {"dark", "🌙 تیره"}};
        for (String[] o : opts) {
            boolean sel = o[0].equals(cur);
            TextView ch = new TextView(this);
            ch.setText(o[1]);
            ch.setGravity(Gravity.CENTER);
            ch.setTextSize(14);
            ch.setTextColor(sel ? ON_ACCENT : TEXT);
            if (sel) ch.setTypeface(Typeface.DEFAULT_BOLD);
            ch.setPadding(dp(8), dp(11), dp(8), dp(11));
            ch.setBackground(ripple(sel ? ACCENT : CARD, sel ? 0 : LINE, dp(12)));
            final String id = o[0];
            ch.setOnClickListener(v -> {
                if (id.equals(Prefs.theme(this))) return;
                Prefs.setTheme(this, id);
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMarginEnd(dp(6));
            chips.addView(ch, lp);
        }
        k.addView(chips);
        para(k, "«خودکار» همراه با حالت روشن/تیره‌ی گوشی عوض می‌شود.").setPadding(0, dp(8), 0, 0);

        LinearLayout k2 = card();
        heading(k2, "سرور");
        Switch auto = switchRow(k2, "روشن شدن خودکار بعد از ری‌استارت گوشی", Prefs.autoStart(this));
        auto.setOnCheckedChangeListener((b, v) -> Prefs.setAutoStart(this, v));
        final TextView portBtn = button(k2, "", false, v -> askPort());

        LinearLayout kp = card();
        heading(kp, "🌐 اشتراک اینترنت گوشی با کامپیوتر");
        para(kp, "کامپیوترِ شبکه‌ی داخلی با تنظیم پروکسی از اینترنت گوشی (داده همراه، یا فیلترشکن اگر روشن باشد) استفاده می‌کند.");
        Switch px = switchRow(kp, "روشن", Prefs.proxyOn(this));
        px.setOnCheckedChangeListener((b, v) -> ServerService.setProxy(this, v));
        Switch pr = switchRow(kp, "فقط دستگاه‌هایی که با رمز وارد پیام‌بین شده‌اند", Prefs.proxyRestrict(this));
        pr.setOnCheckedChangeListener((b, v) -> Prefs.setProxyRestrict(this, v));
        final TextView pxInfo = text(13, TEXT, false);
        pxInfo.setTextIsSelectable(true);
        pxInfo.setPadding(0, dp(6), 0, 0);
        kp.addView(pxInfo);
        final TextView capBtn = button(kp, "", false, v -> askCap());
        Switch sch = switchRow(kp, "فقط در ساعت کاری", Prefs.proxySched(this));
        sch.setOnCheckedChangeListener((b, v) -> {
            Prefs.setProxySched(this, v, null, null);
            ProxyServer.enforce();
        });
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        kp.addView(times);
        final TextView startBtn = button(times, "", false, v -> pickTime(true));
        final TextView endBtn = button(times, "", false, v -> pickTime(false));
        startBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, -2, 1);
        elp.setMarginStart(dp(8));
        endBtn.setLayoutParams(elp);
        button(kp, "پورت اشتراک اینترنت", false, v -> askProxyPort());
        button(kp, "پاک کردن دستگاه‌های مجاز", false, v -> {
            ProxyServer.clearAllowed(this);
            Toast.makeText(this, "پاک شد؛ هر دستگاه باید دوباره وارد صفحه‌ی پیام‌بین شود", Toast.LENGTH_LONG).show();
        });

        LinearLayout kcam = card();
        heading(kcam, "📷 دوربین");
        Switch sb = switchRow(kcam, "همیشه آماده (روشن شدن از کامپیوتر بدون تأیید روی گوشی)", Prefs.camStandby(this));
        sb.setOnCheckedChangeListener((b, v) -> {
            Prefs.setCamStandby(this, v);
            if (v) CameraService.ensureStandby(this);
            else CameraService.disable(this);
        });
        para(kcam, "بعد از ری‌استارت گوشی یک بار برنامه را باز کنید تا دوباره آماده شود. هر کس رمز پیام‌بین را داشته باشد می‌تواند دوربین را روشن کند؛ نقطه‌ی سبز و اعلان «در حال پخش» همیشه دیده می‌شود.");

        LinearLayout k3 = card();
        heading(k3, "امنیت");
        final TextView pinTv = para(k3, "");
        button(k3, "ساخت رمز جدید (خروج همه‌ی مرورگرها)", false, v -> dialog()
                .setMessage("رمز جدید ساخته شود؟ همه‌ی مرورگرهایی که وارد شده‌اند باید دوباره رمز را بزنند.")
                .setPositiveButton("بله", (d, w) -> {
                    Prefs.newPin(this);
                    Toast.makeText(this, "رمز جدید ساخته شد", Toast.LENGTH_SHORT).show();
                    if (pageRefresh != null) pageRefresh.run();
                })
                .setNegativeButton("انصراف", null).show());
        final TextView keyTv = para(k3, "");
        keyTv.setTextIsSelectable(true);
        button(k3, "ساخت کلید API جدید", false, v -> dialog()
                .setMessage("کلید فعلی API باطل شود؟ اسکریپت‌ها و افزونه‌ی رمز پویا باید کلید جدید را بگیرند.")
                .setPositiveButton("بله", (d, w) -> {
                    Prefs.newApiKey(this);
                    if (pageRefresh != null) pageRefresh.run();
                })
                .setNegativeButton("انصراف", null).show());

        pageRefresh = () -> {
            java.util.List<String> ips = Net.localIps();
            String ip = ips.isEmpty() ? "?" : ips.get(0);
            int cap = Prefs.proxyCapMb(this);
            capBtn.setText("سقف مصرف روزانه: " + (cap == 0 ? "نامحدود" : cap + " MB") + "  (تغییر)");
            startBtn.setText("از " + Prefs.proxyStart(this));
            endBtn.setText("تا " + Prefs.proxyEnd(this));
            startBtn.setEnabled(Prefs.proxySched(this));
            endBtn.setEnabled(Prefs.proxySched(this));
            startBtn.setAlpha(Prefs.proxySched(this) ? 1f : 0.45f);
            endBtn.setAlpha(Prefs.proxySched(this) ? 1f : 0.45f);
            if (ProxyServer.isRunning()) {
                org.json.JSONObject u = ProxyServer.usage();
                long total = u.optLong("up") + u.optLong("down");
                StringBuilder dev = new StringBuilder();
                org.json.JSONArray ds = u.optJSONArray("devices");
                for (int i = 0; ds != null && i < ds.length(); i++) {
                    org.json.JSONObject d = ds.optJSONObject(i);
                    dev.append("\n   • ").append(d.optString("ip")).append(": ").append(mb(d.optLong("up") + d.optLong("down")));
                }
                String block = ProxyServer.blockReason();
                pxInfo.setText((block != null ? "⛔ " + block + "\n" : "")
                        + "وضعیت: " + ProxyServer.status + "\nمسیر اینترنت: " + ProxyServer.route
                        + "\nمصرف امروز: " + mb(total) + (cap > 0 ? " از " + cap + " MB" : "")
                        + "  · اتصال فعال: " + ProxyServer.active.get()
                        + (dev.length() > 0 ? "\nمصرف هر دستگاه:" + dev : "")
                        + (Prefs.proxyRestrict(this) ? "\nدستگاه‌های مجاز: " + ProxyServer.allowedList(this) : "\nدسترسی: همه‌ی دستگاه‌های شبکه‌ی داخلی")
                        + "\n\nدر ویندوز ← Settings ← Network ← Proxy:\n• Use setup script: http://" + ip + ":" + Prefs.port(this) + "/proxy.pac"
                        + "\nیا Manual proxy: " + ip + " پورت " + Prefs.proxyPort(this) + " (تیک «local addresses» را بزنید)");
            } else {
                pxInfo.setText(Prefs.proxyOn(this) ? "وضعیت: " + ProxyServer.status : "خاموش است");
            }
            portBtn.setText("پورت: " + Prefs.port(this) + "  (تغییر)");
            pinTv.setText("رمز ورود فعلی: " + Prefs.pin(this));
            keyTv.setText("\nکلید API: " + Prefs.apiKey(this));
        };
    }

    private void pageUpdate() {
        LinearLayout k = card();
        heading(k, "نسخه‌ی نصب‌شده: " + Updater.currentVersion(this));
        para(k, "برنامه هر ۶ ساعت نسخه‌ی جدید را از گیت‌هاب بررسی و خودکار نصب می‌کند.");
        final TextView st = text(14, TEXT, false);
        st.setPadding(0, dp(10), 0, 0);
        k.addView(st);
        button(k, "بررسی آپدیت الان", true, v -> Updater.checkAsync(this));
        pageRefresh = () -> {
            String s = Updater.status;
            st.setText(s == null || s.isEmpty() ? "—" : s);
        };
    }

    private void pageDiag() {
        LinearLayout k = card();
        heading(k, "رویدادهای اخیر");
        para(k, "خاموش/روشن شدن صفحه، حالت چرت، قطع وای‌فای و دسترسی اعلان با ساعت ثبت می‌شود. اگر ارتباط قطع می‌شود، این گزارش را بفرستید.");
        final TextView logTv = text(11, TEXT, false);
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setTextIsSelectable(true);
        logTv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        logTv.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(dp(10));
        logTv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        k.addView(logTv, lp);
        Runnable load = () -> {
            String log = Diag.read(30_000);
            logTv.setText(log.isEmpty() ? "هنوز چیزی ثبت نشده" : log);
        };
        load.run();
        button(k, "ارسال / اشتراک گزارش", true, v -> {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, Diag.read(60_000));
            startActivity(Intent.createChooser(s, "ارسال گزارش"));
        });
        button(k, "تازه کردن", false, v -> load.run());
        button(k, "پاک کردن گزارش", false, v -> {
            Diag.clear();
            load.run();
        });
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void pageAbout() {
        LinearLayout k = card();
        k.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        k.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView name = text(22, TEXT, true);
        name.setText("پیام‌بین");
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(8), 0, 0);
        k.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView ver = text(13, MUTED, false);
        ver.setText("نسخه‌ی " + Updater.currentVersion(this) + " · PayamBin");
        ver.setGravity(Gravity.CENTER);
        k.addView(ver, new LinearLayout.LayoutParams(-1, -2));
        TextView desc = text(14, TEXT, false);
        desc.setText("\nپیامک‌ها، اعلان پیام‌رسان‌ها و تماس‌های گوشی را در مرورگر کامپیوتر نشان می‌دهد؛ بدون اینکه مدام گوشی را روشن کنید.");
        desc.setGravity(Gravity.CENTER);
        k.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout k2 = card();
        heading(k2, "امکانات");
        para(k2, "• پیامک‌ها با نام مخاطب، جستجو و ارسال از کامپیوتر\n"
                + "• اعلان بله، تلگرام، واتساپ و …\n"
                + "• تماس ورودی و فهرست تماس‌ها\n"
                + "• اعلان ویندوز و کپی یک‌کلیکی رمز پویا\n"
                + "• API رمز پویا برای پرکردن خودکار\n"
                + "• باتری گوشی، آدرس ثابت payambin.local\n"
                + "• آپدیت خودکار");

        LinearLayout k3 = card();
        heading(k3, "حریم خصوصی");
        para(k3, "همه‌چیز فقط داخل شبکه‌ی خودتان می‌ماند. پیام‌ها به هیچ سروری در اینترنت فرستاده نمی‌شوند؛ تنها ارتباط اینترنتی برنامه بررسی آپدیت از گیت‌هاب است. ورود با رمز است و فقط دستگاه‌های شبکه‌ی داخلی اجازه‌ی اتصال دارند.");

        LinearLayout k4 = card();
        heading(k4, "سورس و نسخه‌ها");
        button(k4, "صفحه‌ی برنامه در گیت‌هاب", false, v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/adriarai118/PayamBin")));
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------- کارها ----------------
    private void askPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.SEND_SMS);
        p.add(Manifest.permission.READ_CONTACTS);
        p.add(Manifest.permission.READ_PHONE_STATE);
        p.add(Manifest.permission.READ_CALL_LOG);
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
            p.add(Manifest.permission.READ_MEDIA_IMAGES);
            p.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(p.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pageRefresh != null) pageRefresh.run();
        // اگر میکروفون تازه اجازه گرفت، سرویس دوربین را از نو با نوع «میکروفون» آماده کن
        if (SmsRepo.has(this, Manifest.permission.RECORD_AUDIO) && !"on".equals(CameraService.state)) {
            CameraService.disable(this);
            handler.postDelayed(() -> CameraService.ensureStandby(this), 800);
        }
        if (!SmsRepo.has(this, Manifest.permission.READ_SMS)) {
            dialog().setMessage("مجوز پیامک داده نشد. اگر گزینه غیرفعال بود از تنظیمات برنامه ← منوی سه‌نقطه ← Allow restricted settings را بزنید و دوباره امتحان کنید.")
                    .setPositiveButton("تنظیمات برنامه", (d, w) -> openAppDetails())
                    .setNegativeButton("بستن", null)
                    .show();
        }
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void openXiaomiAutostart() {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setClassName(t[0], t[1]);
                i.putExtra("package_name", getPackageName());
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void askPort() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(Prefs.port(this)));
        et.setTextColor(TEXT);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog().setTitle("پورت (۱۰۲۴ تا ۶۵۵۳۵)")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p < 1024 || p > 65535) throw new NumberFormatException();
                        Prefs.setPort(this, p);
                        if (ServerService.running) {
                            ServerService.stop(this);
                            handler.postDelayed(() -> ServerService.start(this), 600);
                        }
                        if (pageRefresh != null) handler.postDelayed(pageRefresh, 900);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "عدد نامعتبر", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private static String mb(long b) {
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void askCap() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("۰ = نامحدود");
        int cur = Prefs.proxyCapMb(this);
        if (cur > 0) et.setText(String.valueOf(cur));
        et.setTextColor(TEXT);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog().setTitle("سقف مصرف روزانه (مگابایت)")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String t = Otp.latinDigits(et.getText().toString().trim());
                    int v = 0;
                    try {
                        v = t.isEmpty() ? 0 : Integer.parseInt(t);
                    } catch (NumberFormatException ignored) {
                    }
                    Prefs.setProxyCapMb(this, v);
                    ProxyServer.enforce();
                    if (pageRefresh != null) pageRefresh.run();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void pickTime(boolean start) {
        String cur = start ? Prefs.proxyStart(this) : Prefs.proxyEnd(this);
        int h = 7, m = 30;
        try {
            h = Integer.parseInt(cur.split(":")[0]);
            m = Integer.parseInt(cur.split(":")[1]);
        } catch (Exception ignored) {
        }
        new android.app.TimePickerDialog(this, dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert, (tp, hh, mm) -> {
            String v = String.format(Locale.US, "%02d:%02d", hh, mm);
            Prefs.setProxySched(this, Prefs.proxySched(this), start ? v : null, start ? null : v);
            ProxyServer.enforce();
            if (pageRefresh != null) pageRefresh.run();
        }, h, m, true).show();
    }

    private void askProxyPort() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(Prefs.proxyPort(this)));
        et.setTextColor(TEXT);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog().setTitle("پورت اشتراک اینترنت")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p < 1024 || p > 65535 || p == Prefs.port(this)) throw new NumberFormatException();
                        Prefs.setProxyPort(this, p);
                        if (ProxyServer.isRunning()) {
                            ProxyServer.stop();
                            ProxyServer.start(this);
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "عدد نامعتبر", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void copyAddress(TextView address) {
        CharSequence a = address.getText();
        if (a == null || !a.toString().startsWith("http")) return;
        String first = a.toString().split("\n")[0];
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("address", first));
        Toast.makeText(this, "آدرس کپی شد", Toast.LENGTH_SHORT).show();
    }
}
