package com.hfpengine.s9;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public final class StandbyService extends Service {
    public static volatile boolean running = false;
    @Override public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(101, AppSupport.notification(this,
                "S9 HFP Engine — standby", "Boot monitor active; tap to arm microphone"));
        Log.i("HfpEngine", "Standby foreground service started (no microphone, no HCI)");
    }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
}
