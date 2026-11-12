package com.adriarai.payambin;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** عکس‌ها و ویدیوهای اخیر گوشی */
public final class MediaRepo {
    private MediaRepo() {}

    public static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33)
            return SmsRepo.has(c, Manifest.permission.READ_MEDIA_IMAGES) || SmsRepo.has(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        return SmsRepo.has(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private static Uri base(String kind) {
        return kind.equals("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    public static Uri uri(String kind, long id) {
        return ContentUris.withAppendedId(base(kind), id);
    }

    private static void query(Context c, String kind, int limit, List<JSONObject> out) {
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE};
        try (Cursor cur = c.getContentResolver().query(base(kind), cols, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return;
            int n = 0;
            while (cur.moveToNext() && n++ < limit) {
                out.add(J.obj("id", cur.getLong(0), "kind", kind, "name", cur.getString(1),
                        "size", cur.getLong(2), "time", cur.getLong(3) * 1000L, "mime", cur.getString(4)));
            }
        } catch (Exception ignored) {
        }
    }

    public static JSONArray recent(Context c, int limit) {
        List<JSONObject> all = new ArrayList<>();
        if (!allowed(c)) return new JSONArray();
        query(c, "image", limit, all);
        query(c, "video", limit, all);
        java.util.Collections.sort(all, (a, b) -> Long.compare(b.optLong("time"), a.optLong("time")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(limit, all.size()); i++) out.put(all.get(i));
        return out;
    }

    public static JSONObject info(Context c, String kind, long id) {
        String[] cols = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE};
        try (Cursor cur = c.getContentResolver().query(uri(kind, id), cols, null, null, null)) {
            if (cur != null && cur.moveToFirst())
                return J.obj("name", cur.getString(0), "size", cur.getLong(1), "mime", cur.getString(2));
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    public static byte[] thumb(Context c, String kind, long id) {
        try {
            Bitmap bm;
            if (Build.VERSION.SDK_INT >= 29) {
                bm = c.getContentResolver().loadThumbnail(uri(kind, id), new Size(320, 320), null);
            } else if (kind.equals("video")) {
                bm = MediaStore.Video.Thumbnails.getThumbnail(c.getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
            } else {
                bm = MediaStore.Images.Thumbnails.getThumbnail(c.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
            if (bm == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 78, bo);
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
