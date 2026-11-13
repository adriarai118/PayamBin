package com.adriarai.payambin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * اتصال دائمی گوشی به سرور واسطه (VPS) برای دسترسی به پیام‌بین از اینترنت.
 * هر درخواست مرورگر به‌صورت یک «جریان» می‌رسد و به وب‌سرور محلی گوشی داده می‌شود.
 */
public final class RelayClient {
    static final byte F_OPEN = 1, F_DATA = 2, F_END = 3, F_RESP = 4, F_CLOSE = 5, F_PING = 6, F_PONG = 7;
    static final int MAX_FRAME = 64 * 1024;

    public static volatile String status = "خاموش";
    private static volatile boolean running = false;
    private static Thread thread;
    private static volatile Socket sock;
    private static Context app;
    private static volatile Network cellular;
    private static ConnectivityManager.NetworkCallback cellCb;

    private static final Object wlock = new Object();
    private static OutputStream out;
    private static final Map<Integer, Socket> streams = new ConcurrentHashMap<>();

    /** کد راه‌اندازی: pbrelay://host:port?k=KEY&p=PIN */
    public static final class Setup {
        public String host, key, pin;
        public int port;

        public static Setup parse(String code) {
            if (code == null) return null;
            code = code.trim();
            if (!code.startsWith("pbrelay://")) return null;
            try {
                Uri u = Uri.parse(code.replace("pbrelay://", "https://"));
                Setup s = new Setup();
                s.host = u.getHost();
                s.port = u.getPort() > 0 ? u.getPort() : 8443;
                s.key = u.getQueryParameter("k");
                s.pin = u.getQueryParameter("p");
                if (s.host == null || s.key == null || s.key.isEmpty()) return null;
                return s;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static boolean isConnected() {
        return status.startsWith("وصل");
    }

    public static synchronized void start(Context c) {
        if (running) return;
        app = c.getApplicationContext();
        Setup s = Setup.parse(Prefs.relayCode(app));
        if (s == null) {
            status = "کد راه‌اندازی وارد نشده";
            return;
        }
        running = true;
        keepCellular();
        thread = new Thread(() -> loop(s), "payambin-relay");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        closeQuiet(sock);
        for (Socket st : streams.values()) closeQuiet(st);
        streams.clear();
        releaseCellular();
        status = "خاموش";
    }

    public static void restart(Context c) {
        stop();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        if (Prefs.relayOn(c)) start(c);
    }

    // ---------- مسیر اینترنت: شبکه‌ی اصلی اگر اینترنت دارد، وگرنه داده همراه ----------

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
        } catch (Exception ignored) {
        }
    }

    private static void releaseCellular() {
        try {
            if (cellCb != null && app != null)
                ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(cellCb);
        } catch (Exception ignored) {
        }
        cellCb = null;
        cellular = null;
    }

    private static Socket rawSocket(String host, int port) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network def = cm.getActiveNetwork();
        NetworkCapabilities caps = def == null ? null : cm.getNetworkCapabilities(def);
        boolean ok = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        Socket s = new Socket();
        InetAddress addr;
        if (!ok && cellular != null) {
            cellular.bindSocket(s);
            addr = cellular.getByName(host);
        } else {
            addr = InetAddress.getByName(host);
        }
        s.connect(new InetSocketAddress(addr, port), 20000);
        return s;
    }

    // ---------- TLS با «سنجاق» گواهی خودامضا ----------

    private static SSLSocketFactory tlsFactory(final String pin) throws Exception {
        TrustManager tm;
        if (pin == null || pin.isEmpty()) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            tm = tmf.getTrustManagers()[0];
        } else {
            tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        byte[] d = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        StringBuilder sb = new StringBuilder();
                        for (byte b : d) sb.append(String.format("%02x", b));
                        if (!sb.toString().equalsIgnoreCase(pin)) throw new CertificateException("گواهی سرور عوض شده است");
                    } catch (CertificateException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new CertificateException(e);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx.getSocketFactory();
    }

    // ---------- حلقه‌ی اتصال ----------

    private static void loop(Setup s) {
        int backoff = 3000;
        while (running) {
            try {
                status = "در حال اتصال به سرور…";
                Socket raw = rawSocket(s.host, s.port);
                SSLSocket ssl = (SSLSocket) tlsFactory(s.pin).createSocket(raw, s.host, s.port, true);
                ssl.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                ssl.startHandshake();
                sock = ssl;
                ssl.setSoTimeout(75_000);
                OutputStream o = ssl.getOutputStream();
                DataInputStream in = new DataInputStream(new BufferedInputStream(ssl.getInputStream()));
                String req = "GET /__agent HTTP/1.1\r\nHost: " + s.host + "\r\nUpgrade: payambin-agent\r\nConnection: Upgrade\r\nX-Agent-Key: "
                        + s.key + "\r\n\r\n";
                o.write(req.getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                String line = readLine(in);
                if (line == null || !line.contains(" 101 ")) {
                    throw new IOException(line != null && line.contains("403") ? "کلید گوشی در سرور اشتباه است" : "پاسخ نامعتبر سرور: " + line);
                }
                while (true) {
                    String h = readLine(in);
                    if (h == null || h.isEmpty()) break;
                }
                synchronized (wlock) {
                    out = o;
                }
                status = "وصل به سرور ✓";
                backoff = 3000;
                Diag.log("دسترسی از اینترنت: وصل شد به " + s.host);
                readFrames(in);
            } catch (Exception e) {
                if (running) {
                    status = "قطع: " + e.getMessage();
                    Diag.log("دسترسی از اینترنت: " + e.getMessage());
                }
            } finally {
                closeQuiet(sock);
                for (Socket st : streams.values()) closeQuiet(st);
                streams.clear();
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ignored) {
            }
            backoff = Math.min(60_000, backoff * 2);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static void send(byte t, int id, byte[] data, int off, int len) throws IOException {
        synchronized (wlock) {
            if (out == null) throw new IOException("not connected");
            do {
                int n = Math.min(len, MAX_FRAME);
                byte[] hdr = new byte[9];
                hdr[0] = t;
                hdr[1] = (byte) (id >>> 24);
                hdr[2] = (byte) (id >>> 16);
                hdr[3] = (byte) (id >>> 8);
                hdr[4] = (byte) id;
                hdr[5] = (byte) (n >>> 24);
                hdr[6] = (byte) (n >>> 16);
                hdr[7] = (byte) (n >>> 8);
                hdr[8] = (byte) n;
                out.write(hdr);
                if (n > 0) out.write(data, off, n);
                off += n;
                len -= n;
            } while (len > 0);
            out.flush();
        }
    }

    private static void readFrames(DataInputStream in) throws IOException {
        byte[] hdr = new byte[9];
        while (running) {
            in.readFully(hdr);
            byte t = hdr[0];
            int id = ((hdr[1] & 0xff) << 24) | ((hdr[2] & 0xff) << 16) | ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
            int n = ((hdr[5] & 0xff) << 24) | ((hdr[6] & 0xff) << 16) | ((hdr[7] & 0xff) << 8) | (hdr[8] & 0xff);
            if (n < 0 || n > MAX_FRAME) throw new IOException("bad frame");
            byte[] data = new byte[n];
            in.readFully(data);
            switch (t) {
                case F_PING:
                    send(F_PONG, 0, new byte[0], 0, 0);
                    break;
                case F_OPEN:
                    openStream(id, data);
                    break;
                case F_DATA: {
                    Socket st = streams.get(id);
                    if (st != null) {
                        try {
                            st.getOutputStream().write(data);
                        } catch (IOException e) {
                            closeStream(id);
                        }
                    }
                    break;
                }
                case F_END:
                    // بدنه‌ی درخواست تمام شد (وب‌سرور با Content-Length می‌خواند)
                    break;
                case F_CLOSE:
                    closeStream(id);
                    break;
                default:
                    break;
            }
        }
    }

    private static void closeStream(int id) {
        Socket st = streams.remove(id);
        closeQuiet(st);
    }

    /** درخواست مرورگر → وب‌سرور محلی گوشی */
    private static void openStream(int id, byte[] json) {
        try {
            JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
            String method = o.optString("method", "GET");
            String path = o.optString("path", "/");
            JSONObject h = o.optJSONObject("headers");
            Socket local = new Socket();
            local.connect(new InetSocketAddress("127.0.0.1", Prefs.port(app)), 5000);
            streams.put(id, local);
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: 127.0.0.1\r\n");
            if (h != null) {
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("host") || lk.equals("connection")) continue;
                    String v = h.optString(k).replace("\r", "").replace("\n", "");
                    sb.append(k).append(": ").append(v).append("\r\n");
                }
            }
            sb.append("Connection: close\r\n\r\n");
            local.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Thread t = new Thread(() -> pumpResponse(id, local), "relay-s" + id);
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            closeStream(id);
            try {
                send(F_CLOSE, id, new byte[0], 0, 0);
            } catch (IOException ignored) {
            }
        }
    }

    /** پاسخ وب‌سرور محلی → سرور واسطه */
    private static void pumpResponse(int id, Socket local) {
        try {
            InputStream in = new BufferedInputStream(local.getInputStream());
            int code;
            JSONObject headers;
            boolean chunked;
            while (true) {
                String status = readLine(in);
                if (status == null) throw new IOException("empty");
                String[] p = status.split(" ", 3);
                code = p.length > 1 ? Integer.parseInt(p[1].trim()) : 502;
                headers = new JSONObject();
                chunked = false;
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    int i = h.indexOf(':');
                    if (i <= 0) continue;
                    String k = h.substring(0, i).trim(), v = h.substring(i + 1).trim();
                    if (k.equalsIgnoreCase("Connection")) continue;
                    if (k.equalsIgnoreCase("Transfer-Encoding")) {
                        chunked = v.toLowerCase(Locale.ROOT).contains("chunked");
                        continue;
                    }
                    headers.put(k, v);
                }
                if (code >= 200 || code < 100) break; // پاسخ‌های میانی 1xx (مثل 100 Continue) رد شوند
            }
            byte[] head = J.obj("status", code, "headers", headers).toString().getBytes(StandardCharsets.UTF_8);
            send(F_RESP, id, head, 0, head.length);
            byte[] buf = new byte[32 * 1024];
            if (chunked) {
                while (true) {
                    String sz = readLine(in);
                    if (sz == null) break;
                    int semi = sz.indexOf(';');
                    int len = Integer.parseInt((semi >= 0 ? sz.substring(0, semi) : sz).trim(), 16);
                    if (len == 0) break;
                    while (len > 0) {
                        int n = in.read(buf, 0, Math.min(buf.length, len));
                        if (n <= 0) throw new IOException("eof");
                        send(F_DATA, id, buf, 0, n);
                        len -= n;
                    }
                    readLine(in); // CRLF بعد از هر تکه
                }
            } else {
                int n;
                while ((n = in.read(buf)) > 0) send(F_DATA, id, buf, 0, n);
            }
            send(F_END, id, new byte[0], 0, 0);
        } catch (Exception e) {
            try {
                send(F_CLOSE, id, new byte[0], 0, 0);
            } catch (IOException ignored) {
            }
        } finally {
            closeStream(id);
        }
    }

    private static void closeQuiet(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }
}
