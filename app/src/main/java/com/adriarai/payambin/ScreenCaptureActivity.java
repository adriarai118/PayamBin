package com.adriarai.payambin;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** پنجره‌ی شفاف که رضایت اندروید برای ضبط صفحه را می‌گیرد */
public class ScreenCaptureActivity extends Activity {
    private static final int REQ = 77;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            ScreenService.state = "error";
            ScreenService.error = "ضبط صفحه پشتیبانی نمی‌شود: " + e.getMessage();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenService.class).setAction(ScreenService.ACTION_START)
                    .putExtra("code", resultCode).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } else {
            ScreenService.state = "error";
            ScreenService.error = "اجازه‌ی ضبط صفحه روی گوشی داده نشد";
        }
        finish();
    }
}
