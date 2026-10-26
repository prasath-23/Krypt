package com.krypt.e2e.target;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * Shows a marker text the end-to-end test looks for to know the app is really open. Every tap that
 * reaches the app's window is logged, so the test can tell a tap went through Krypt's countdown.
 */
public class TargetActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView marker = new TextView(this);
        marker.setText("E2E target is open");
        marker.setTextSize(24f);
        marker.setGravity(Gravity.CENTER);
        setContentView(marker);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) Log.i("KryptE2ETarget", "tapped");
        return super.dispatchTouchEvent(event);
    }
}
