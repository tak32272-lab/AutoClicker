package com.example.autoclicker;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** 高速モードの「画面全体を共有」の許可を求めるだけの透明な画面 */
public class ProjectionActivity extends Activity {

    private static final int REQ = 1;
    private boolean asked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        asked = savedInstanceState != null && savedInstanceState.getBoolean("asked");
        if (asked) return;
        asked = true;
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent i = Build.VERSION.SDK_INT >= 34
                    ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : mpm.createScreenCaptureIntent();
            startActivityForResult(i, REQ);
        } catch (Throwable e) {
            ClickerService.onCaptureDenied();
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent s = new Intent(this, CaptureService.class)
                    .putExtra("code", resultCode)
                    .putExtra("data", data);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
                else startService(s);
            } catch (Throwable e) {
                ClickerService.onCaptureDenied();
            }
        } else {
            ClickerService.onCaptureDenied();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
