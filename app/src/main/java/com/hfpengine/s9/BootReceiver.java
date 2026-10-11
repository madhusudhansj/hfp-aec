package com.hfpengine.s9;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "HfpEngine";
    @Override public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Standby boot receiver: " + intent.getAction());
        if (!context.getSharedPreferences(AppSupport.PREFS, Context.MODE_PRIVATE)
                .getBoolean("boot_standby", false)) return;
        // NEVER start AudioService here: microphone access can be denied on a boot start.
        try {
            context.startForegroundService(new Intent(context, StandbyService.class));
        } catch (RuntimeException ex) {
            Log.e(TAG, "Could not start standby service after boot", ex);
        }
    }
}
