package com.adriarai.payambin;

import java.nio.ByteBuffer;

/** تبدیل YUV_420_888 به NV21 (بدون وابستگی به اندروید؛ قابل تست) */
final class Yuv {
    private Yuv() {}

    /** آیا صفحه‌های U و V در حافظه به‌صورت NV21 (VUVU…) روی هم افتاده‌اند؟ */
    static boolean uvIsNv21(ByteBuffer u, ByteBuffer v) {
        try {
            if (v.capacity() < 2 || u.capacity() < 1) return false;
            byte saved = v.get(1);
            byte changed = (byte) ~saved;
            v.put(1, changed);
            boolean same = u.get(0) == changed;
            v.put(1, saved);
            return same;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] toNv21(int w, int h,
                         ByteBuffer y, int yRow, int yPix,
                         ByteBuffer u, int uRow, int uPix,
                         ByteBuffer v, int vRow, int vPix) {
        byte[] out = new byte[w * h * 3 / 2];
        int pos = 0;
        if (yRow == w && yPix == 1) {
            y.position(0);
            y.get(out, 0, w * h);
            pos = w * h;
        } else {
            for (int r = 0; r < h; r++) {
                y.position(r * yRow);
                y.get(out, pos, w);
                pos += w;
            }
        }
        // مسیر سریع: داده‌ها از قبل NV21 هستند → کپی ردیف‌به‌ردیف
        if (uPix == 2 && vPix == 2 && uRow == vRow && uvIsNv21(u, v)) {
            int rows = h / 2;
            for (int r = 0; r < rows; r++) {
                int start = r * vRow;
                int len = Math.min(w, v.capacity() - start);
                v.position(start);
                v.get(out, pos, len);
                if (len < w) out[pos + w - 1] = u.get(Math.min(u.capacity() - 1, start + w - 2));
                pos += w;
            }
            return out;
        }
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                out[pos++] = v.get(r * vRow + c * vPix);
                out[pos++] = u.get(r * uRow + c * uPix);
            }
        }
        return out;
    }
}
