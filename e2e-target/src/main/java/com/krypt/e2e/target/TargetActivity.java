package com.krypt.e2e.target;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Shows a marker text the end-to-end test looks for to know the app is really open. */
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
}
