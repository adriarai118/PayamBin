package com.adriarai.payambin;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * پاسخ‌دهندهٔ سادهٔ mDNS تا آدرس payambin.local در ویندوز به IP گوشی برسد
 * (بدون وابستگی به اندروید؛ قفل multicast در ServerService گرفته می‌شود).
 */
public class Mdns {
    public static final String HOST = "payambin.local";
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;

    public static volatile String status = "";

    private volatile boolean running;
    private MulticastSocket sock;

    public void start() {
        running = true;
        Thread t = new Thread(this::loop, "payambin-mdns");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }

    /** IP فعلی گوشی روی وای‌فای */
    static Inet4Address currentIp() {
        List<String> ips = Net.localIps();
        if (ips.isEmpty()) return null;
        try {
            InetAddress a = InetAddress.getByName(ips.get(0));
            return a instanceof Inet4Address ? (Inet4Address) a : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void loop() {
        while (running) {
            Inet4Address boundIp = currentIp();
            try {
                InetAddress group = InetAddress.getByName(GROUP);
                sock = new MulticastSocket(PORT); // SO_REUSEADDR فعال است
                sock.setTimeToLive(255);
                sock.setSoTimeout(30_000);
                NetworkInterface ni = boundIp == null ? null : NetworkInterface.getByInetAddress(boundIp);
                if (ni != null) {
                    sock.setNetworkInterface(ni);
                    sock.joinGroup(new InetSocketAddress(group, PORT), ni);
                } else {
                    sock.joinGroup(group);
                }
                status = "فعال";
                announce(group);

                byte[] buf = new byte[9000];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                    } catch (java.net.SocketTimeoutException te) {
                        // اگر IP عوض شد، سوکت را از نو بساز
                        Inet4Address now = currentIp();
                        if (now != null && !now.equals(boundIp)) break;
                        continue;
                    }
                    handle(p, group);
                }
            } catch (Exception e) {
                if (running) status = "غیرفعال: " + e.getMessage();
            } finally {
                try {
                    if (sock != null) sock.close();
                } catch (Exception ignored) {
                }
            }
            if (running) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void announce(InetAddress group) {
        Inet4Address ip = currentIp();
        if (ip == null) return;
        byte[] r = buildResponse(0, false, false, ip.getAddress());
        for (int i = 0; i < 2; i++) {
            try {
                sock.send(new DatagramPacket(r, r.length, group, PORT));
                Thread.sleep(300);
            } catch (Exception ignored) {
            }
        }
    }

    private void handle(DatagramPacket p, InetAddress group) {
        byte[] b = p.getData();
        int len = p.getLength();
        Query q = parseQuery(b, len);
        if (q == null || !q.matches) return;
        Inet4Address ip = currentIp();
        if (ip == null) return;
        try {
            if (p.getPort() != PORT) {
                // پرسش «legacy unicast»: پاسخ مستقیم با همان شناسه و بخش پرسش
                byte[] r = buildResponse(q.id, true, true, ip.getAddress());
                sock.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
            } else {
                byte[] r = buildResponse(0, false, false, ip.getAddress());
                sock.send(new DatagramPacket(r, r.length, group, PORT));
                if (q.unicastWanted) sock.send(new DatagramPacket(r, r.length, p.getAddress(), PORT));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- کدگذاری DNS (قابل تست بدون اندروید) ----------------

    static final class Query {
        int id;
        boolean matches, unicastWanted;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    static String readName(byte[] b, int[] posRef, int len) {
        StringBuilder sb = new StringBuilder();
        int pos = posRef[0];
        boolean jumped = false;
        int guard = 0;
        while (true) {
            if (pos >= len) throw new IllegalArgumentException("bad name");
            int l = b[pos] & 0xff;
            if (l == 0) {
                pos++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len) throw new IllegalArgumentException("bad ptr");
                int ptr = ((l & 0x3f) << 8) | (b[pos + 1] & 0xff);
                if (!jumped) posRef[0] = pos + 2;
                jumped = true;
                pos = ptr;
                if (++guard > 30) throw new IllegalArgumentException("loop");
                continue;
            }
            pos++;
            if (pos + l > len) throw new IllegalArgumentException("bad label");
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, pos, l, StandardCharsets.ISO_8859_1));
            pos += l;
        }
        if (!jumped) posRef[0] = pos;
        return sb.toString();
    }

    static Query parseQuery(byte[] b, int len) {
        try {
            if (len < 12) return null;
            int flags = u16(b, 2);
            if ((flags & 0x8000) != 0) return null; // پاسخ است، نه پرسش
            Query q = new Query();
            q.id = u16(b, 0);
            int qd = u16(b, 4);
            int[] pos = {12};
            for (int i = 0; i < qd; i++) {
                String name = readName(b, pos, len);
                int type = u16(b, pos[0]);
                int cls = u16(b, pos[0] + 2);
                pos[0] += 4;
                if (name.equalsIgnoreCase(HOST) && (type == 1 || type == 255)) {
                    q.matches = true;
                    if ((cls & 0x8000) != 0) q.unicastWanted = true;
                }
            }
            return q;
        } catch (Exception e) {
            return null;
        }
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xff);
        o.write(v & 0xff);
    }

    private static void wName(ByteArrayOutputStream o) {
        for (String label : HOST.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            o.write(l.length);
            o.write(l, 0, l.length);
        }
        o.write(0);
    }

    static byte[] buildResponse(int id, boolean withQuestion, boolean legacy, byte[] ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, id);
        w16(o, 0x8400); // پاسخ معتبر (QR + AA)
        w16(o, withQuestion ? 1 : 0);
        w16(o, 1);
        w16(o, 0);
        w16(o, 0);
        if (withQuestion) {
            wName(o);
            w16(o, 1);
            w16(o, 1);
        }
        wName(o);
        w16(o, 1);                          // A
        w16(o, legacy ? 0x0001 : 0x8001);   // IN (+ cache-flush)
        w16(o, 0);
        w16(o, 1800);                       // TTL = ۳۰ دقیقه (با عوض شدن IP دوباره اعلام می‌شود)
        w16(o, 4);
        o.write(ip, 0, 4);
        return o.toByteArray();
    }
}
