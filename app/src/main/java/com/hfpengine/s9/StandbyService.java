package com.hfpengine.s9;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public final class StandbyService extends Service {
    public static volatile boolean running = false;
    public static volatile String status = "Bluetooth standby disabled";
    private RootBridge bridge;
    @Override public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(101, AppSupport.notification(this,
                "S9 HFP Engine — standby", "Waiting for Bluetooth call audio; microphone closed"));
        bridge=new RootBridge(this);bridge.start();
        Log.i("HfpEngine", "Root bridge standby started; audio streams remain closed");
    }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { running = false;if(bridge!=null)bridge.close();status="Bluetooth standby disabled";super.onDestroy(); }
}
