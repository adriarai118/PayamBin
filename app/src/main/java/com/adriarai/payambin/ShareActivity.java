package com.adriarai.payambin;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** «اشتراک‌گذاری ← پیام‌بین»: متن یا فایل را برای کامپیوتر می‌فرستد */
public class ShareActivity extends Activity {

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.init(this);
        Intent i = getIntent();
        int files = 0;
        String text = null;
        try {
            String action = i.getAction();
            if (Intent.ACTION_SEND.equals(action)) {
                Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (p instanceof Uri) {
                    if (save((Uri) p)) files++;
                } else {
                    CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                    if (t != null) text = t.toString();
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Parcelable> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) for (Parcelable p : list) if (p instanceof Uri && save((Uri) p)) files++;
            }
            if (text != null && !text.isEmpty()) {
                Clip.add(text, "phone");
                Toast.makeText(this, "متن برای کامپیوتر فرستاده شد", Toast.LENGTH_SHORT).show();
            } else if (files > 0) {
                NotifStore.Versions.files.incrementAndGet();
                Toast.makeText(this, files + " فایل برای کامپیوتر آماده شد", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "چیزی برای فرستادن نبود", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "خطا: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private boolean save(Uri uri) {
        String name = "file";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) {
        }
        File f = FileStore.newFile(this, "out", name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(f)) {
            if (in == null) return false;
            FileStore.copy(in, out, -1);
            Diag.log("فایل برای کامپیوتر: " + f.getName());
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return false;
        }
    }
}
