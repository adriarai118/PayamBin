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
        final List<String> headers = new ArrayList<>();

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

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            if (!Net.isLocal(sock.getInetAddress())) return;
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
        sb.append("Content-Length: ").append(r.body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Connection: close\r\n");
        for (String h : r.headers) sb.append(h).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(r.body);
        out.flush();
    }

    // ---------------- مسیرها ----------------

    private Res route(Req r) throws Exception {
        String path = r.path;

        if (path.equals("/") || path.equals("/index.html")) {
            Res res = new Res();
            res.type = "text/html; charset=utf-8";
            res.body = index();
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

        String token = r.cookie("pb");
        if (!Prefs.validToken(ctx, token)) return Res.err(401, "login required");

        switch (path) {
            case "/api/logout":
                Prefs.removeToken(ctx, token);
                return Res.json(J.obj("ok", true));

            case "/api/state":
                return Res.json(J.obj(
                        "sms", NotifStore.Versions.sms.get(),
                        "notif", NotifStore.Versions.notif.get(),
                        "perms", SmsRepo.perms(ctx),
                        "device", Build.MANUFACTURER + " " + Build.MODEL));

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

            case "/api/notifs":
                return Res.json(J.obj(
                        "items", NotifStore.get(ctx).list(r.query.get("pkg"), r.qi("limit", 300)),
                        "apps", NotifStore.get(ctx).apps()));

            case "/api/notifs/clear":
                if (!r.method.equals("POST")) return Res.err(400, "POST required");
                NotifStore.get(ctx).clear(r.json().optString("pkg", ""));
                return Res.json(J.obj("ok", true));

            default:
                return Res.err(404, "not found");
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
