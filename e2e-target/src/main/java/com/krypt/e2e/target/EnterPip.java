package com.krypt.e2e.target;

import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Puts the open target into picture-in-picture: the end-to-end test's app in a small window. */
public class EnterPip extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        TargetActivity activity = TargetActivity.current;
        if (activity == null) {
            Log.w("KryptE2ETarget", "no target open to put in picture-in-picture");
            return;
        }
        boolean entered = activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
        Log.i("KryptE2ETarget", "picture-in-picture: " + entered);
    }
}
