package com.adriarai.payambin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int ACCENT = Color.parseColor("#0F766E");

    private TextView status, address, pin, permInfo, updInfo;
    private Button installPermBtn;
    private Button toggle, permsBtn, notifBtn, batteryBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("پیام‌بین");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        // کارت آدرس
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0FDFA"));
        bg.setStroke(dp(1), Color.parseColor("#99F6E4"));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        root.addView(card, lp(0));

        status = text(15, Color.parseColor("#334155"), false);
        card.addView(status);

        address = text(24, ACCENT, true);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.CENTER);
        address.setPadding(0, dp(10), 0, dp(4));
        address.setOnClickListener(v -> copyAddress());
        card.addView(address);

        TextView hint = text(12, Color.parseColor("#64748B"), false);
        hint.setGravity(Gravity.CENTER);
        hint.setText("یکی از این آدرس‌ها را در مرورگر کامپیوتر (همان شبکه) باز کنید؛ اگر اولی باز نشد، دومی (IP) را بزنید — برای کپی لمس کنید");
        card.addView(hint);

        pin = text(18, Color.parseColor("#0F172A"), true);
        pin.setGravity(Gravity.CENTER);
        pin.setPadding(0, dp(12), 0, 0);
        card.addView(pin);

        toggle = button("");
        toggle.setOnClickListener(v -> {
            if (ServerService.running) ServerService.stop(this);
            else {
                ServerService.lastError = null;
                ServerService.start(this);
            }
            handler.postDelayed(this::refresh, 400);
        });
        root.addView(toggle, lp(14));

        // مجوزها
        TextView h = text(16, Color.parseColor("#0F172A"), true);
        h.setText("مجوزها");
        h.setPadding(0, dp(18), 0, dp(4));
        root.addView(h);

        permInfo = text(14, Color.parseColor("#334155"), false);
        permInfo.setLineSpacing(dp(3), 1f);
        root.addView(permInfo);

        permsBtn = button("دادن مجوز پیامک، تماس‌ها و مخاطبین");
        permsBtn.setOnClickListener(v -> askPerms());
        root.addView(permsBtn, lp(8));

        notifBtn = button("دسترسی به اعلان‌ها (بله، تلگرام، واتساپ و ...)");
        notifBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        root.addView(notifBtn, lp(8));

        batteryBtn = button("جلوگیری از بسته شدن توسط بهینه‌ساز باتری");
        batteryBtn.setOnClickListener(v -> askBattery());
        root.addView(batteryBtn, lp(8));

        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            Button auto = button("شیائومی: روشن کردن Autostart");
            auto.setOnClickListener(v -> openXiaomiAutostart());
            root.addView(auto, lp(8));
            Button bs = button("شیائومی: صرفه‌جویی باتری ← No restrictions");
            bs.setOnClickListener(v -> openAppDetails());
            root.addView(bs, lp(8));
        }

        // تنظیمات
        TextView h2 = text(16, Color.parseColor("#0F172A"), true);
        h2.setText("تنظیمات");
        h2.setPadding(0, dp(18), 0, dp(4));
        root.addView(h2);

        CheckBox auto = new CheckBox(this);
        auto.setText("روشن شدن خودکار بعد از ری‌استارت گوشی");
        auto.setChecked(Prefs.autoStart(this));
        auto.setOnCheckedChangeListener((b, c) -> Prefs.setAutoStart(this, c));
        root.addView(auto);

        Button newPin = button("ساخت رمز جدید (خروج همهٔ مرورگرها)");
        newPin.setOnClickListener(v -> {
            Prefs.newPin(this);
            refresh();
            Toast.makeText(this, "رمز جدید ساخته شد", Toast.LENGTH_SHORT).show();
        });
        root.addView(newPin, lp(8));

        Button portBtn = button("تغییر پورت");
        portBtn.setOnClickListener(v -> askPort());
        root.addView(portBtn, lp(8));

        // آپدیت
        TextView h3 = text(16, Color.parseColor("#0F172A"), true);
        h3.setText("آپدیت");
        h3.setPadding(0, dp(18), 0, dp(4));
        root.addView(h3);

        updInfo = text(14, Color.parseColor("#334155"), false);
        root.addView(updInfo);

        installPermBtn = button("اجازهٔ نصب آپدیت‌ها");
        installPermBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            }
        });
        root.addView(installPermBtn, lp(8));

        Button checkUpd = button("بررسی آپدیت الان");
        checkUpd.setOnClickListener(v -> Updater.checkAsync(this));
        root.addView(checkUpd, lp(8));

        TextView help = text(13, Color.parseColor("#64748B"), false);
        help.setLineSpacing(dp(3), 1f);
        help.setPadding(0, dp(18), 0, 0);
        help.setText("• گوشی و کامپیوتر باید به یک مودم/وای‌فای وصل باشند.\n"
                + "• اگر دکمهٔ دسترسی اعلان‌ها خاکستری بود: تنظیمات ← برنامه‌ها ← پیام‌بین ← منوی سه‌نقطه ← «Allow restricted settings».\n"
                + "• در گوشی‌های شیائومی/هواوی/سامسونگ، «Autostart» را روشن و محدودیت باتری را «بدون محدودیت» کنید.\n"
                + "• فقط دستگاه‌های داخل شبکهٔ خانگی/اداره به سرور دسترسی دارند و ورود با رمز است.");
        root.addView(help);

        setContentView(scroll);

        // بار اول: سرور را روشن کن
        if (savedInstanceState == null && !ServerService.running) ServerService.start(this);
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void refresh() {
        boolean run = ServerService.running;
        String addr = ServerService.address(this);
        if (run) {
            status.setText("● سرور روشن است");
            status.setTextColor(Color.parseColor("#15803D"));
            address.setText(addr == null ? "به وای‌فای وصل نیستید"
                    : "http://" + Mdns.HOST + ":" + Prefs.port(this) + "\n" + addr);
            pin.setText("رمز ورود: " + Prefs.pin(this));
            toggle.setText("خاموش کردن سرور");
        } else {
            String err = ServerService.lastError;
            status.setText(err != null ? "● " + err : "● سرور خاموش است");
            status.setTextColor(Color.parseColor("#B91C1C"));
            address.setText(addr == null ? "—" : addr);
            pin.setText("رمز ورود: " + Prefs.pin(this));
            toggle.setText("روشن کردن سرور");
        }

        boolean sms = SmsRepo.has(this, Manifest.permission.READ_SMS);
        boolean send = SmsRepo.has(this, Manifest.permission.SEND_SMS);
        boolean contacts = SmsRepo.has(this, Manifest.permission.READ_CONTACTS);
        boolean notif = NotifListener.isEnabled(this);
        boolean callLog = SmsRepo.has(this, Manifest.permission.READ_CALL_LOG);
        boolean battery = ignoringBattery();
        permInfo.setText(mark(sms) + " خواندن پیامک\n"
                + mark(send) + " ارسال پیامک از کامپیوتر\n"
                + mark(contacts) + " نمایش نام مخاطبین\n"
                + mark(callLog) + " نمایش تماس‌ها\n"
                + mark(notif) + " اعلان پیام‌رسان‌ها\n"
                + mark(battery) + " معافیت از بهینه‌سازی باتری");
        permsBtn.setVisibility(sms && send && contacts && callLog ? View.GONE : View.VISIBLE);
        notifBtn.setText(notif ? "تنظیمات دسترسی اعلان‌ها" : "دسترسی به اعلان‌ها (بله، تلگرام، واتساپ و ...)");
        batteryBtn.setVisibility(battery ? View.GONE : View.VISIBLE);

        String us = Updater.status;
        updInfo.setText("نسخهٔ نصب‌شده: " + Updater.currentVersion(this)
                + (us == null || us.isEmpty() ? "" : "\n" + us));
        boolean canInstall = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        installPermBtn.setVisibility(canInstall ? View.GONE : View.VISIBLE);
    }

    private static String mark(boolean ok) {
        return ok ? "✅" : "⬜";
    }

    private void askPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.SEND_SMS);
        p.add(Manifest.permission.READ_CONTACTS);
        p.add(Manifest.permission.READ_PHONE_STATE);
        p.add(Manifest.permission.READ_CALL_LOG);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
        if (!SmsRepo.has(this, Manifest.permission.READ_SMS)) {
            new AlertDialog.Builder(this)
                    .setMessage("مجوز پیامک داده نشد. اگر گزینه غیرفعال بود از تنظیمات برنامه ← منوی سه‌نقطه ← Allow restricted settings را بزنید و دوباره امتحان کنید.")
                    .setPositiveButton("تنظیمات برنامه", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("بستن", null)
                    .show();
        }
    }

    private boolean ignoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return true;
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
        new AlertDialog.Builder(this)
                .setTitle("پورت (۱۰۲۴ تا ۶۵۵۳۵)")
                .setView(et)
                .setPositiveButton("ذخیره", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p < 1024 || p > 65535) throw new NumberFormatException();
                        Prefs.setPort(this, p);
                        if (ServerService.running) {
                            ServerService.stop(this);
                            handler.postDelayed(() -> ServerService.start(this), 600);
                        }
                        handler.postDelayed(this::refresh, 900);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "عدد نامعتبر", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void copyAddress() {
        CharSequence a = address.getText();
        if (a == null || !a.toString().startsWith("http")) return;
        a = a.toString().split("\n")[0];
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("address", a));
        Toast.makeText(this, "آدرس کپی شد", Toast.LENGTH_SHORT).show();
    }
}
