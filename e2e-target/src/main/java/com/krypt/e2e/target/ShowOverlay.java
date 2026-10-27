package com.krypt.e2e.target;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Shows a small window of this app over whatever is in front for ten seconds, without taking focus:
 * the end-to-end test's stand-in for another app's window showing while the app in front keeps focus,
 * as the launcher's does behind an app during a cancelled home gesture. Needs "display over other
 * apps", which the test allows with appops.
 */
public class ShowOverlay extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        WindowManager windows = app.getSystemService(WindowManager.class);
        TextView view = new TextView(app);
        view.setText("E2E overlay");
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(0xCC000000);
        view.setPadding(32, 16, 32, 16);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 400;
        params.setTitle("KryptE2EOverlay");
        windows.addView(view, params);
        Log.i("KryptE2ETarget", "overlay shown");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            windows.removeView(view);
            Log.i("KryptE2ETarget", "overlay gone");
        }, 10_000);
    }
}
