# PayamBin Relay — سرور واسطه برای دسترسی به پیام‌بین از اینترنت

یک فایل اجرایی بدون پیش‌نیاز. گوشی خودش به این سرور وصل می‌شود؛ شما از هر جا با مرورگر و یک رمز قوی وارد می‌شوید.

```
مرورگر (هر جا) ──HTTPS──> VPS (این برنامه) <──اتصال رمزدار── گوشی (پیام‌بین)
```

## نصب (اوبونتو/دبیان، با کاربر root)

```bash
mkdir -p /opt/payambin-relay && cd /opt/payambin-relay
# فایل مناسب را کپی کنید: payambin-relay-linux-amd64 (بیشتر سرورها) یا ...-arm64
mv payambin-relay-linux-amd64 payambin-relay && chmod +x payambin-relay

# بار اول: اجرا برای دیدن رمز و کد راه‌اندازی (YOUR_SERVER_IP را با IP سرور عوض کنید)
./payambin-relay -listen :8443 -host YOUR_SERVER_IP -dir /opt/payambin-relay
#   ← «رمز ورود» و «کد راه‌اندازی گوشی» را یادداشت کنید، سپس Ctrl+C

# باز کردن پورت در فایروال (اگر ufw دارید)
ufw allow 8443/tcp

# اجرای دائمی (بعد از ری‌استارت سرور هم)
cp payambin-relay.service /etc/systemd/system/
sed -i 's/YOUR_SERVER_IP/1.2.3.4/' /etc/systemd/system/payambin-relay.service   # 1.2.3.4 = IP سرور شما
systemctl daemon-reload && systemctl enable --now payambin-relay
journalctl -u payambin-relay -n 20     # دیدن دوباره‌ی رمز و کد
```

## روی گوشی
پیام‌بین ← ☰ ← تنظیمات ← «دسترسی از اینترنت» ← «وارد کردن کد راه‌اندازی سرور» ← کد `pbrelay://…` را بچسبانید ← کلید «روشن».

## از هر جا
مرورگر: `https://IP-سرور:8443` ← هشدار «اتصال خصوصی نیست» را یک بار رد کنید (Advanced ← Proceed) ← رمز ورود.

## امنیت
- رمز ورود ۱۶ حرفی تصادفی است؛ بعد از ۶ تلاش اشتباه، آن IP برای ۱۵ دقیقه قفل می‌شود.
- اتصال گوشی با کلید جدا و «سنجاق» گواهی سرور است؛ اگر کسی بین راه گواهی دیگری بدهد گوشی وصل نمی‌شود.
- ترافیک رمزنگاری‌شده (TLS) است. هشدار مرورگر فقط به‌خاطر خودامضا بودن گواهی است.
- رمز جدید: `./payambin-relay -new-password -dir /opt/payambin-relay` و ری‌استارت سرویس.
- اگر دامنه دارید: با `-cert fullchain.pem -key privkey.pem` (مثلاً از certbot) گواهی معتبر بدهید تا هشدار مرورگر نیاید؛ در این حالت کد راه‌اندازی سنجاق ندارد.

## پرچم‌ها
| پرچم | توضیح |
|---|---|
| `-listen :8443` | پورت |
| `-host` | IP یا دامنه‌ی عمومی (فقط برای ساختن کد راه‌اندازی) |
| `-dir` | پوشه‌ی تنظیمات (`relay.json` و گواهی) |
| `-cert` / `-key` | گواهی دامنه (اختیاری) |
| `-new-password` | ساخت رمز ورود جدید |
