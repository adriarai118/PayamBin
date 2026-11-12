package com.adriarai.payambin;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** وب‌سرور سبک داخل گوشی (بدون کتابخانهٔ جانبی) */
public class WebServer {
    private final Context ctx;
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private byte[] indexHtml;
    private final Map<String, Long> handoffs = new ConcurrentHashMap<>();

    public WebServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newFixedThreadPool(12);
        running = true;
        Thread t = new Thread(this::acceptLoop, "payambin-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    // ---------------- HTTP ----------------

    static class Req {
        String method = "GET", path = "/", ip = "";
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String cookie(String name) {
            String c = headers.get("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String p = part.trim();
                int i = p.indexOf('=');
                if (i > 0 && p.substring(0, i).equals(name)) return p.substring(i + 1);
            }
            return null;
        }

        JSONObject json() throws Exception {
            String s = new String(body, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        }

        int qi(String k, int def) {
            try {
                return Integer.parseInt(query.get(k));
            } catch (Exception e) {
                return def;
            }
        }

        long ql(String k, long def) {
            try {
                return Long.parseLong(query.get(k));
            } catch (Exception e) {
                return def;
            }
        }
    }

    static class Res {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        InputStream stream;     // برای فایل‌های بزرگ
        long length = -1;
        final List<String> headers = new ArrayList<>();

        static Res file(InputStream in, long len, String mime, String name, boolean download) {
            Res r = new Res();
            r.stream = in;
            r.length = len;
            r.type = mime == null ? "application/octet-stream" : mime;
            String enc;
            try {
                enc = java.net.URLEncoder.encode(name == null ? "file" : name, "UTF-8").replace("+", "%20");
            } catch (Exception e) {
                enc = "file";
            }
            r.headers.add("Content-Disposition: " + (download ? "attachment" : "inline") + "; filename=\"file\"; filename*=UTF-8''" + enc);
            return r;
        }

        static Res json(Object o) {
            Res r = new Res();
            r.body = o.toString().getBytes(StandardCharsets.UTF_8);
            return r;
        }

        static Res err(int status, String msg) {
            Res r = json(J.obj("error", msg));
            r.status = status;
            return r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static volatile long lastReq = 0;

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            if (!Net.isLocal(sock.getInetAddress())) return;
            long now = System.currentTimeMillis();
            if (lastReq != 0 && now - lastReq > 45_000)
                Diag.log("درخواست از " + sock.getInetAddress().getHostAddress() + " بعد از " + ((now - lastReq) / 1000) + " ثانیه سکوت");
            lastReq = now;
            InputStream in = new java.io.BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] p = line.split(" ");
            if (p.length < 2) return;
            Req req = new Req();
            req.method = p[0].toUpperCase(Locale.ROOT);
            req.ip = sock.getInetAddress().getHostAddress();
            String target = p[1];
            int q = target.indexOf('?');
            req.path = q >= 0 ? target.substring(0, q) : target;
            if (q >= 0) {
                for (String kv : target.substring(q + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int e = kv.indexOf('=');
                    if (e < 0) req.query.put(dec(kv), "");
                    else req.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                }
            }
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) req.headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(req.headers.get("content-length"));
            } catch (Exception ignored) {
            }
            if (req.path.equals("/api/upload") && req.method.equals("POST")) {
                Res res;
                try {
                    res = upload(req, in, len);
                } catch (Exception e) {
                    res = Res.err(500, String.valueOf(e.getMessage()));
                }
                write(sock.getOutputStream(), res);
                return;
            }
            if (len > 0) {
                if (len > 1024 * 1024) return;
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                req.body = body;
            }

            Res res;
            try {
                res = route(req);
            } catch (Exception e) {
                res = Res.err(500, String.valueOf(e.getMessage()));
            }
            write(sock.getOutputStream(), res);
        } catch (Exception ignored) {
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String statusText(int s) {
        switch (s) {
            case 200: return "OK";
            case 204: return "No Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            default: return "Internal Server Error";
        }
    }

    private static void write(OutputStream out, Res r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(statusText(r.status)).append("\r\n");
        sb.append("Content-Type: ").append(r.type).append("\r\n");
        if (r.stream == null) sb.append("Content-Length: ").append(r.body.length).append("\r\n");
        else if (r.length >= 0) sb.append("Content-Length: ").append(r.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Connection: close\r\n");
        for (String h : r.headers) sb.append(h).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (r.stream != null) {
            try (InputStream in = r.stream) {
                FileStore.copy(in, out, -1);
            }
        } else out.write(r.body);
        out.flush();
    }

    // ---------------- مسیرها ----------------

    private Res route(Req r) throws Exception {
        String path = r.path;

        if (path.equals("/") || path.equals("/index.html")) {
            // انتقال از payambin.local به IP مستقیم با حفظ ورود (توکن یک‌بارمصرف)
            String h = r.query.get("h");
            if (h != null) {
                Long exp = handoffs.remove(h);
                Res res = new Res();
                res.status = 302;
                res.type = "text/plain; charset=utf-8";
                res.headers.add("Location: /");
                if (exp != null && exp > System.currentTimeMillis()) {
                    res.headers.add("Set-Cookie: pb=" + Prefs.newToken(ctx) + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
                }
                return res;
            }
            Res res = new Res();
            res.type = "text/html; charset=utf-8";
            res.body = index();
            return res;
        }
        if (path.equals("/proxy.pac")) {
            java.util.List<String> ips = Net.localIps();
            Res res = new Res();
            res.type = "application/x-ns-proxy-autoconfig";
            res.body = ProxyServer.pac(ips.isEmpty() ? "127.0.0.1" : ips.get(0), Prefs.proxyPort(ctx)).getBytes(StandardCharsets.UTF_8);
            return res;
        }
        if (path.equals("/favicon.ico")) {
            Res res = new Res();
            res.status = 204;
            return res;
        }
        if (!path.startsWith("/api/")) return Res.err(404, "not found");

        // POST فقط با JSON (جلوگیری از ارسال فرم از سایت‌های دیگر)
        if (r.method.equals("POST")) {
            String ct = r.headers.get("content-type");
            if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json"))
                return Res.err(400, "json required");
        }

        if (path.equals("/api/login") && r.method.equals("POST")) return login(r);

        // رمز پویا برای اسکریپت‌ها: با کلید API یا کوکی ورود
        if (path.equals("/api/otp")) {
            String key = r.headers.get("x-api-key");
            if (key == null) key = r.query.get("key");
            boolean ok = (key != null && key.equals(Prefs.apiKey(ctx))) || Prefs.validToken(ctx, r.cookie("pb"));
            if (!ok) return Res.err(401, "invalid api key");
            return otp(r);
        }

        String token = r.cookie("pb");
        if (!Prefs.validToken(ctx, token)) return Res.err(401, "login required");
        ProxyServer.allow(ctx, r.ip); // این دستگاه می‌تواند از اینترنت گوشی استفاده کند

        switch (path) {
            case "/api/logout":
                Prefs.removeToken(ctx, token);
                return Res.json(J.obj("ok", true));

            case "/api/state":
                return Res.json(J.obj(
                        "sms", NotifStore.Versions.sms.get(),
                        "notif", NotifStore.Versions.notif.get(),
                        "calls", NotifStore.Versions.calls.get(),
                        "files", NotifStore.Versions.files.get(),
                        "clip", NotifStore.Versions.clip.get(),
                        "ringing", Ringer.ringing,
                        "proxy", proxyInfo(),
                        "perms", SmsRepo.perms(ctx),
                        "device", Build.MANUFACTURER + " " + Build.MODEL,
                        "battery", PhoneState.battery(ctx),
                        "call", PhoneState.call(ctx),
                        "otp", cachedOtp(),
                        "addr", J.obj("host", Mdns.HOST, "mdns", Mdns.status,
                                "ips", new JSONArray(Net.localIps()), "port", Prefs.port(ctx))));

            case "/api/notifs/reply": {
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                JSONObject b = r.json();
                JSONObject n = NotifStore.get(ctx).byId(b.optLong("id"));
                String text = b.optString("text", "").trim();
                if (n == null || text.isEmpty()) return Res.err(400, "bad request");
                String err = NotifListener.reply(ctx, n.optString("conv"), text);
                if (err != null) return Res.err(409, err);
                NotifStore.get(ctx).add(J.obj("pkg", n.optString("pkg"), "app", n.optString("app"),
                        "title", n.optString("title"), "sender", "شما", "text", text,
                        "time", System.currentTimeMillis(), "conv", n.optString("conv"), "out", true));
                return Res.json(J.obj("ok", true));
            }

            case "/api/media":
                if (!MediaRepo.allowed(ctx)) return Res.err(403, "no media permission");
                return Res.json(MediaRepo.recent(ctx, r.qi("limit", 60)));

            case "/api/media/thumb": {
                byte[] t = MediaRepo.thumb(ctx, r.query.get("kind") == null ? "image" : r.query.get("kind"), r.ql("id", -1));
                if (t == null) return Res.err(404, "no thumb");
                Res res = new Res();
                res.type = "image/jpeg";
                res.body = t;
                return res;
            }

            case "/api/media/file": {
                String kind = "video".equals(r.query.get("kind")) ? "video" : "image";
                long id = r.ql("id", -1);
                JSONObject info = MediaRepo.info(ctx, kind, id);
                if (info == null) return Res.err(404, "not found");
                InputStream in = ctx.getContentResolver().openInputStream(MediaRepo.uri(kind, id));
                return Res.file(in, info.optLong("size", -1), info.optString("mime"), info.optString("name"),
                        !"0".equals(r.query.get("dl")));
            }

            case "/api/files":
                return Res.json(J.obj("in", FileStore.list(ctx, "in"), "out", FileStore.list(ctx, "out")));

            case "/api/files/get": {
                String box = "out".equals(r.query.get("box")) ? "out" : "in";
                java.io.File f = FileStore.get(ctx, box, r.query.get("name"));
                if (f == null) return Res.err(404, "not found");
                return Res.file(new java.io.FileInputStream(f), f.length(), FileStore.mime(f.getName()), f.getName(),
                        !"0".equals(r.query.get("dl")));
            }

            case "/api/files/delete": {
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                JSONObject b = r.json();
                java.io.File f = FileStore.get(ctx, "out".equals(b.optString("box")) ? "out" : "in", b.optString("name"));
                if (f != null) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                NotifStore.Versions.files.incrementAndGet();
                return Res.json(J.obj("ok", true));
            }

            case "/api/files/share": {
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                JSONObject b = r.json();
                FileStore.offerShare(ctx, b.optString("name"), b.optString("pkg", ""), b.optString("caption", ""));
                return Res.json(J.obj("ok", true));
            }

            case "/api/clip":
                if (r.method.equals("POST")) {
                    String t = r.json().optString("text", "");
                    if (t.isEmpty()) return Res.err(400, "empty");
                    Clip.setPhone(ctx, t);
                    return Res.json(J.obj("ok", true));
                }
                return Res.json(Clip.list());

            case "/api/ring":
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                Ringer.start(ctx);
                return Res.json(J.obj("ok", true));

            case "/api/ring/stop":
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                Ringer.stop(ctx);
                return Res.json(J.obj("ok", true));

            case "/api/proxy":
                if (r.method.equals("POST")) {
                    JSONObject b = r.json();
                    if (b.has("on")) ServerService.setProxy(ctx, b.optBoolean("on"));
                    if (b.optBoolean("clearClients")) {
                        ProxyServer.clearAllowed(ctx);
                        ProxyServer.allow(ctx, r.ip);
                    }
                }
                return Res.json(proxyInfo());

            case "/api/handoff": {
                java.util.List<String> ips = Net.localIps();
                if (ips.isEmpty()) return Res.json(J.obj("url", null));
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Long> e : handoffs.entrySet()) if (e.getValue() < now) handoffs.remove(e.getKey());
                handoffs.put(sb.toString(), now + 60_000L);
                return Res.json(J.obj("url", "http://" + ips.get(0) + ":" + Prefs.port(ctx) + "/?h=" + sb));
            }

            case "/api/diag": {
                Res d = new Res();
                d.type = "text/plain; charset=utf-8";
                d.body = Diag.read(120_000).getBytes(StandardCharsets.UTF_8);
                return d;
            }

            case "/api/calls":
                return Res.json(PhoneState.calls(ctx, r.qi("limit", 200)));

            case "/api/apikey":
                return Res.json(J.obj("key", Prefs.apiKey(ctx)));

            case "/api/apikey/new":
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                return Res.json(J.obj("key", Prefs.newApiKey(ctx)));

            case "/api/threads":
                if (!SmsRepo.has(ctx, Manifest.permission.READ_SMS)) return Res.err(403, "no READ_SMS permission");
                return Res.json(SmsRepo.threads(ctx, r.qi("limit", 300)));

            case "/api/messages":
                if (!SmsRepo.has(ctx, Manifest.permission.READ_SMS)) return Res.err(403, "no READ_SMS permission");
                return Res.json(SmsRepo.messages(ctx, r.ql("thread", -1), r.qi("limit", 400)));

            case "/api/search": {
                if (!SmsRepo.has(ctx, Manifest.permission.READ_SMS)) return Res.err(403, "no READ_SMS permission");
                String q = r.query.get("q");
                if (q == null || q.trim().isEmpty()) return Res.json(new JSONArray());
                return Res.json(SmsRepo.search(ctx, q.trim(), 200));
            }

            case "/api/sims":
                return Res.json(SmsRepo.sims(ctx));

            case "/api/send": {
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                if (!SmsRepo.has(ctx, Manifest.permission.SEND_SMS)) return Res.err(403, "no SEND_SMS permission");
                JSONObject b = r.json();
                String to = b.optString("address", "").trim();
                String body = b.optString("body", "");
                if (to.isEmpty() || body.trim().isEmpty()) return Res.err(400, "address and body required");
                SmsRepo.send(ctx, to, body, b.optInt("sub", -1));
                return Res.json(J.obj("ok", true));
            }

            case "/api/notifs": {
                JSONArray items = NotifStore.get(ctx).list(r.query.get("pkg"), r.qi("limit", 300));
                JSONArray outItems = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = new JSONObject(items.getJSONObject(i).toString());
                    J.put(o, "canReply", NotifListener.canReply(o.optString("conv")));
                    outItems.put(o);
                }
                return Res.json(J.obj(
                        "items", outItems,
                        "apps", NotifStore.get(ctx).apps()));
            }

            case "/api/notifs/clear":
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                NotifStore.get(ctx).clear(r.json().optString("pkg", ""));
                return Res.json(J.obj("ok", true));

            default:
                return Res.err(404, "not found");
        }
    }

    /** POST /api/upload?name=...  (بدنه = خود فایل) */
    private Res upload(Req r, InputStream in, int len) throws Exception {
        if (!Prefs.validToken(ctx, r.cookie("pb"))) return Res.err(401, "login required");
        // هدر سفارشی: جلوی ارسال از سایت‌های دیگر را می‌گیرد
        if (!"1".equals(r.headers.get("x-payambin"))) return Res.err(400, "bad request");
        if (len <= 0) return Res.err(400, "empty");
        java.io.File f = FileStore.receive(ctx, r.query.get("name"), in, len);
        return Res.json(J.obj("ok", true, "name", f.getName()));
    }

    private JSONObject proxyInfo() {
        java.util.List<String> ips = Net.localIps();
        return J.obj("on", ProxyServer.isRunning(), "wanted", Prefs.proxyOn(ctx),
                "status", ProxyServer.status, "route", ProxyServer.route,
                "ip", ips.isEmpty() ? null : ips.get(0), "port", Prefs.proxyPort(ctx),
                "up", ProxyServer.bytesUp.get(), "down", ProxyServer.bytesDown.get(),
                "active", ProxyServer.active.get(),
                "clients", new JSONArray(ProxyServer.allowedList(ctx)));
    }

    private String otpCacheKey = "";
    private JSONObject otpCache = null;

    /** آخرین کد ۳ دقیقهٔ اخیر برای نمایش در صفحه (فقط با تغییر داده‌ها دوباره حساب می‌شود) */
    private synchronized JSONObject cachedOtp() {
        String k = NotifStore.Versions.sms.get() + ":" + NotifStore.Versions.notif.get() + ":" + (System.currentTimeMillis() / 30000);
        if (!k.equals(otpCacheKey) || otpCache == null) {
            otpCache = Otp.latest(ctx, 180_000L, 0);
            otpCacheKey = k;
        }
        return otpCache;
    }

    /** GET /api/otp?key=..&max_age=180&since=<ms>&wait=<s> */
    private Res otp(Req r) throws Exception {
        long maxAge = Math.max(10, Math.min(3600, r.qi("max_age", 180))) * 1000L;
        long since = r.ql("since", 0);
        int wait = Math.max(0, Math.min(60, r.qi("wait", 0)));
        long deadline = System.currentTimeMillis() + wait * 1000L;
        while (true) {
            JSONObject o = Otp.latest(ctx, maxAge, since);
            if (!o.isNull("code") || System.currentTimeMillis() >= deadline) return Res.json(o);
            Thread.sleep(1000);
        }
    }

    private Res login(Req r) throws Exception {
        Long until = lockedUntil.get(r.ip);
        if (until != null && until > System.currentTimeMillis())
            return Res.err(429, "too many attempts");
        String pin = r.json().optString("pin", "").trim();
        // ارقام فارسی/عربی را به لاتین تبدیل کن
        StringBuilder sb = new StringBuilder();
        for (char ch : pin.toCharArray()) {
            if (ch >= '۰' && ch <= '۹') sb.append((char) ('0' + (ch - '۰')));
            else if (ch >= '٠' && ch <= '٩') sb.append((char) ('0' + (ch - '٠')));
            else sb.append(ch);
        }
        if (!sb.toString().equals(Prefs.pin(ctx))) {
            int f = failures.containsKey(r.ip) ? failures.get(r.ip) + 1 : 1;
            failures.put(r.ip, f);
            if (f >= 5) {
                lockedUntil.put(r.ip, System.currentTimeMillis() + 60_000L);
                failures.remove(r.ip);
            }
            return Res.err(401, "wrong pin");
        }
        failures.remove(r.ip);
        String token = Prefs.newToken(ctx);
        Res res = Res.json(J.obj("ok", true));
        res.headers.add("Set-Cookie: pb=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
        return res;
    }

    private synchronized byte[] index() throws IOException {
        if (indexHtml == null) {
            InputStream in = ctx.getAssets().open("index.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            indexHtml = bo.toByteArray();
        }
        return indexHtml;
    }
}
