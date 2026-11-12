package com.adriarai.payambin;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Net {
    private Net() {}

    /** آدرس‌های IPv4 داخلی گوشی (وای‌فای / هات‌اسپات)، وای‌فای اول */
    public static List<String> localIps() {
        List<String> wifi = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) wifi.add(ip);
                    else other.add(ip);
                }
            }
        } catch (Exception ignored) {
        }
        wifi.addAll(other);
        return wifi;
    }

    /** فقط اتصال از شبکهٔ داخلی پذیرفته می‌شود */
    public static boolean isLocal(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 100 && b1 >= 64 && b1 <= 127; // CGNAT (بعضی هات‌اسپات‌ها)
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true; // ULA fc00::/7
            // IPv4-mapped
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                int b0 = b[12] & 0xff, b1 = b[13] & 0xff;
                return b0 == 10 || b0 == 127 || (b0 == 192 && b1 == 168) || (b0 == 172 && b1 >= 16 && b1 <= 31)
                        || (b0 == 169 && b1 == 254);
            }
        }
        return false;
    }
}
