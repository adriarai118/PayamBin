package com.adriarai.payambin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * پروکسی HTTP/HTTPS: اینترنت گوشی (داده همراه یا فیلترشکن) را به کامپیوترِ شبکه‌ی داخلی می‌رساند.
 * پیش‌فرض: هر دستگاه شبکه‌ی داخلی با تنظیم پروکسی؛ اختیاری: فقط دستگاه‌های واردشده.
 */
public final class ProxyServer {
    private ProxyServer() {}

    public static final AtomicLong bytesUp = new AtomicLong(), bytesDown = new AtomicLong();
    public static final AtomicInteger active = new AtomicInteger();
    public static volatile String route = "—";      // مسیر فعلی اینترنت
    public static volatile String status = "خاموش";
    public static volatile String lastClient = "";

    private static ServerSocket ss;
    private static ExecutorService pool;
    private static volatile boolean running;
    private static Context app;
    private static volatile Network cellular;
    private static ConnectivityManager.NetworkCallback cellCb;

    // ---------- شروع / توقف ----------

    public static synchronized void start(Context c) {
        if (running) return;
        app = c.getApplicationContext();
        int port = Prefs.proxyPort(app);
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            status = "پورت " + port + " آزاد نیست";
            Diag.log("پروکسی روشن نشد: " + e.getMessage());
            return;
        }
        pool = Executors.newCachedThreadPool();
        running = true;
        status = "روشن روی پورت " + port;
        keepCellular();
        Thread t = new Thread(ProxyServer::acceptLoop, "payambin-proxy");
        t.setDaemon(true);
        t.start();
        Diag.log("اشتراک اینترنت روشن شد (پورت " + port + ")");
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        save();
        closeAll();
        try {
            ss.close();
        } catch (Exception ignored) {
        }
        if (pool != null) pool.shutdownNow();
        releaseCellular();
        status = "خاموش";
        route = "—";
        Diag.log("اشتراک اینترنت خاموش شد");
    }

    public static boolean isRunning() {
        return running;
    }

    /** داده همراه را بیدار نگه دار، حتی وقتی وای‌فای (بدون اینترنت) شبکه‌ی اصلی است */
    private static void keepCellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cellCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    cellular = n;
                }

                @Override
                public void onLost(Network n) {
                    if (n.equals(cellular)) cellular = null;
                }
            };
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(), cellCb);
        } catch (Exception e) {
            Diag.log("درخواست داده همراه ممکن نشد: " + e.getMessage());
        }
    }

    private static void releaseCellular() {
        try {
            if (cellCb != null)
                ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(cellCb);
        } catch (Exception ignored) {
        }
        cellCb = null;
        cellular = null;
    }

    // ---------- مصرف روزانه، سقف و ساعت کاری ----------

    private static final java.util.Map<String, long[]> devUsage = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong dayUp = new AtomicLong(), dayDown = new AtomicLong();
    private static String usageDay = "";
    private static long lastSave = 0;
    private static final Set<Socket> openSockets =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    private static synchronized void ensureDay() {
        String t = today();
        if (usageDay.isEmpty() && app != null) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(Prefs.proxyUsage(app));
                if (t.equals(o.optString("day"))) {
                    dayUp.set(o.optLong("up"));
                    dayDown.set(o.optLong("down"));
                    org.json.JSONObject d = o.optJSONObject("dev");
                    if (d != null) {
                        java.util.Iterator<String> it = d.keys();
                        while (it.hasNext()) {
                            String ip = it.next();
                            org.json.JSONArray a = d.optJSONArray(ip);
                            if (a != null) devUsage.put(ip, new long[]{a.optLong(0), a.optLong(1)});
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            usageDay = t;
        }
        if (!t.equals(usageDay)) {
            dayUp.set(0);
            dayDown.set(0);
            devUsage.clear();
            usageDay = t;
            save();
        }
    }

    private static synchronized void save() {
        if (app == null) return;
        try {
            org.json.JSONObject d = new org.json.JSONObject();
            for (java.util.Map.Entry<String, long[]> e : devUsage.entrySet())
                d.put(e.getKey(), new org.json.JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            Prefs.setProxyUsage(app, J.obj("day", usageDay, "up", dayUp.get(), "down", dayDown.get(), "dev", d).toString());
        } catch (Exception ignored) {
        }
        lastSave = System.currentTimeMillis();
    }

    /** شمارش مصرف؛ اگر سقف پر شد true برمی‌گرداند */
    private static boolean count(String ip, boolean up, int n) {
        ensureDay();
        (up ? bytesUp : bytesDown).addAndGet(n);
        (up ? dayUp : dayDown).addAndGet(n);
        long[] u = devUsage.get(ip);
        if (u == null) {
            u = new long[2];
            devUsage.put(ip, u);
        }
        synchronized (u) {
            u[up ? 0 : 1] += n;
        }
        if (System.currentTimeMillis() - lastSave > 30_000) save();
        int cap = Prefs.proxyCapMb(app);
        return cap > 0 && dayUp.get() + dayDown.get() >= cap * 1048576L;
    }

    private static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** اگر الان نباید سرویس داد، دلیلش؛ وگرنه null */
    public static String blockReason() {
        if (app == null) return null;
        ensureDay();
        if (Prefs.proxySched(app)) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            int s = minutes(Prefs.proxyStart(app)), e = minutes(Prefs.proxyEnd(app));
            boolean in = s <= e ? (now >= s && now < e) : (now >= s || now < e);
            if (!in) return "خارج از ساعت کاری (" + Prefs.proxyStart(app) + " تا " + Prefs.proxyEnd(app) + ")";
        }
        int cap = Prefs.proxyCapMb(app);
        if (cap > 0 && dayUp.get() + dayDown.get() >= cap * 1048576L)
            return "سقف مصرف امروز (" + cap + " مگابایت) پر شده";
        return null;
    }

    private static volatile String lastBlock = null;

    /** هر نیم دقیقه از سرویس صدا زده می‌شود: اتصال‌های باز را در صورت لزوم قطع می‌کند */
    public static void enforce() {
        if (!running) return;
        String r = blockReason();
        if (r != null && !r.equals(lastBlock)) Diag.log("اشتراک اینترنت متوقف شد: " + r);
        lastBlock = r;
        if (r != null) closeAll();
    }

    private static void closeAll() {
        for (Socket s : openSockets) close(s);
    }

    public static org.json.JSONObject usage() {
        ensureDay();
        org.json.JSONArray devs = new org.json.JSONArray();
        java.util.List<java.util.Map.Entry<String, long[]>> list = new java.util.ArrayList<>(devUsage.entrySet());
        java.util.Collections.sort(list, (a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        for (java.util.Map.Entry<String, long[]> e : list)
            devs.put(J.obj("ip", e.getKey(), "up", e.getValue()[0], "down", e.getValue()[1]));
        return J.obj("day", usageDay, "up", dayUp.get(), "down", dayDown.get(), "devices", devs);
    }

    // ---------- دستگاه‌های مجاز ----------

    private static final Set<String> allowed = new HashSet<>();
    private static boolean allowedLoaded = false;

    private static synchronized void loadAllowed(Context c) {
        if (allowedLoaded) return;
        allowed.addAll(Prefs.proxyClients(c));
        allowedLoaded = true;
    }

    /** هر دستگاهی که با رمز وارد صفحه‌ی پیام‌بین شود، مجاز می‌شود */
    public static synchronized void allow(Context c, String ip) {
        loadAllowed(c);
        if (ip == null || allowed.contains(ip)) return;
        allowed.add(ip);
        Prefs.setProxyClients(c, allowed);
    }

    public static synchronized boolean isAllowed(Context c, String ip) {
        if (!Prefs.proxyRestrict(c)) return true; // بدون رمز: هر دستگاه شبکه‌ی داخلی
        loadAllowed(c);
        return allowed.contains(ip) || ip.startsWith("127.");
    }

    public static synchronized Set<String> allowedList(Context c) {
        loadAllowed(c);
        return new HashSet<>(allowed);
    }

    public static synchronized void clearAllowed(Context c) {
        allowed.clear();
        allowedLoaded = true;
        Prefs.setProxyClients(c, allowed);
    }

    // ---------- اتصال به اینترنت ----------

    /** سوکت به مقصد: از شبکه‌ی اصلی اگر اینترنت دارد (یا فیلترشکن است)، وگرنه مستقیم از داده همراه */
    private static Socket upstream(String host, int port) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network def = cm.getActiveNetwork();
        NetworkCapabilities caps = def == null ? null : cm.getNetworkCapabilities(def);
        boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        boolean defOk = caps != null && (vpn || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        Socket s;
        InetAddress addr;
        if (defOk) {
            route = vpn ? "فیلترشکن (VPN) گوشی" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "داده همراه" : "اینترنت وای‌فای";
            addr = InetAddress.getByName(host);
            s = new Socket();
        } else {
            Network cell = cellular;
            if (cell == null) {
                route = "اینترنت در دسترس نیست";
                throw new IOException("no internet");
            }
            route = "داده همراه";
            addr = cell.getByName(host);
            s = new Socket();
            cell.bindSocket(s);
        }
        s.connect(new InetSocketAddress(addr, port), 15000);
        s.setSoTimeout(0);
        return s;
    }

    // ---------- پراکسی ----------

    private static void acceptLoop() {
        while (running) {
            try {
                Socket c = ss.accept();
                pool.execute(() -> handle(c));
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (ch == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void reply(OutputStream out, String status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private static void handle(Socket client) {
        Socket up = null;
        active.incrementAndGet();
        try {
            client.setSoTimeout(30000);
            String ip = client.getInetAddress().getHostAddress();
            if (ip.startsWith("::ffff:")) ip = ip.substring(7);
            if (!Net.isLocal(client.getInetAddress())) return;
            InputStream cin = new BufferedInputStream(client.getInputStream());
            OutputStream cout = client.getOutputStream();
            if (!isAllowed(app, ip)) {
                reply(cout, "403 Forbidden", "این دستگاه اجازه‌ی استفاده از اینترنت گوشی را ندارد. یک بار صفحه‌ی پیام‌بین را در همین کامپیوتر باز کنید و با رمز وارد شوید.");
                return;
            }
            String block = blockReason();
            if (block != null) {
                reply(cout, "403 Forbidden", "اشتراک اینترنت گوشی فعلاً بسته است: " + block);
                return;
            }
            lastClient = ip;
            openSockets.add(client);
            String line = readLine(cin);
            if (line == null || line.isEmpty()) return;
            String[] p = line.split(" ");
            if (p.length < 3) return;
            String method = p[0].toUpperCase(Locale.ROOT), target = p[1], version = p[2];

            StringBuilder headers = new StringBuilder();
            String h;
            while ((h = readLine(cin)) != null && !h.isEmpty()) {
                String low = h.toLowerCase(Locale.ROOT);
                if (low.startsWith("proxy-connection:") || low.startsWith("proxy-authorization:")
                        || low.startsWith("connection:") || low.startsWith("keep-alive:")) continue;
                headers.append(h).append("\r\n");
            }

            if (method.equals("CONNECT")) {
                // HTTPS: تونل
                int colon = target.lastIndexOf(':');
                String host = colon > 0 ? target.substring(0, colon) : target;
                int port = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 443;
                try {
                    up = upstream(host.replace("[", "").replace("]", ""), port);
                    openSockets.add(up);
                } catch (IOException e) {
                    reply(cout, "502 Bad Gateway", "گوشی به اینترنت دسترسی ندارد: " + e.getMessage());
                    return;
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                client.setSoTimeout(0);
                pipe(ip, client, cin, up);
                return;
            }

            // HTTP معمولی: GET http://host:port/path
            if (!target.toLowerCase(Locale.ROOT).startsWith("http://")) {
                reply(cout, "400 Bad Request", "PayamBin proxy");
                return;
            }
            String rest = target.substring(7);
            int slash = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            String path = slash >= 0 ? rest.substring(slash) : "/";
            int colon = hostPort.lastIndexOf(':');
            String host = colon > 0 && !hostPort.endsWith("]") ? hostPort.substring(0, colon) : hostPort;
            int port = colon > 0 && !hostPort.endsWith("]") ? Integer.parseInt(hostPort.substring(colon + 1)) : 80;
            try {
                up = upstream(host.replace("[", "").replace("]", ""), port);
                openSockets.add(up);
            } catch (IOException e) {
                reply(cout, "502 Bad Gateway", "گوشی به اینترنت دسترسی ندارد: " + e.getMessage());
                return;
            }
            String head = method + " " + path + " " + version + "\r\n" + headers + "Connection: close\r\n\r\n";
            byte[] hb = head.getBytes(StandardCharsets.ISO_8859_1);
            up.getOutputStream().write(hb);
            count(ip, true, hb.length);
            client.setSoTimeout(0);
            pipe(ip, client, cin, up);
        } catch (Exception ignored) {
        } finally {
            active.decrementAndGet();
            openSockets.remove(client);
            if (up != null) openSockets.remove(up);
            close(client);
            close(up);
        }
    }

    /** دو طرفه: کامپیوتر ⇄ اینترنت (تا وقتی یکی ببندد) */
    private static void pipe(String ip, Socket client, InputStream cin, Socket up) throws IOException {
        final InputStream uin = up.getInputStream();
        final OutputStream uout = up.getOutputStream();
        final OutputStream cout = client.getOutputStream();
        Thread down = new Thread(() -> {
            byte[] buf = new byte[32768];
            int n;
            try {
                while ((n = uin.read(buf)) > 0) {
                    cout.write(buf, 0, n);
                    if (count(ip, false, n)) break; // سقف پر شد
                }
            } catch (IOException ignored) {
            } finally {
                close(client);
                close(up);
            }
        }, "proxy-down");
        down.setDaemon(true);
        down.start();
        byte[] buf = new byte[32768];
        int n;
        try {
            while ((n = cin.read(buf)) > 0) {
                uout.write(buf, 0, n);
                if (count(ip, true, n)) break;
            }
        } catch (IOException ignored) {
        }
        try {
            up.shutdownOutput();
        } catch (Exception ignored) {
        }
        try {
            down.join(120_000);
        } catch (InterruptedException ignored) {
        }
    }

    private static void close(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    /** فایل PAC: آدرس‌های داخلی مستقیم، بقیه از گوشی */
    public static String pac(String phoneIp, int port) {
        return "function FindProxyForURL(url, host) {\n"
                + "  if (isPlainHostName(host) || dnsDomainIs(host, \".local\") ||\n"
                + "      shExpMatch(host, \"10.*\") || shExpMatch(host, \"192.168.*\") || shExpMatch(host, \"127.*\") ||\n"
                + "      /^172\\.(1[6-9]|2[0-9]|3[01])\\./.test(host)) return \"DIRECT\";\n"
                + "  return \"PROXY " + phoneIp + ":" + port + "\";\n"
                + "}\n";
    }
}
