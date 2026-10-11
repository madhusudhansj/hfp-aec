package com.hfpengine.s9;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

public final class EngineService extends Service {
    static final String TAG = "S9SoundEngine";
    static final String START = "com.hfpengine.s9.ENGINE_START";
    static final String STOP = "com.hfpengine.s9.ENGINE_STOP";
    static final String PLAY = "com.hfpengine.s9.ENGINE_PLAY";
    static final String CONFIG = "com.hfpengine.s9.ENGINE_CONFIG";
    static final String RECORD = "com.hfpengine.s9.ENGINE_RECORD";
    static final String EXPORT = "com.hfpengine.s9.ENGINE_EXPORT";
    static volatile boolean running = false;
    static volatile String info = "Not started";
    static volatile boolean recording = false;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(111, AppSupport.notification(this,
                "S9 Sound Engine — active", "Local audio processing and microphone test"));
        // Stop v0.1 to avoid two competing AAudio microphone owners.
        stopService(new Intent(this, AudioService.class));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? "" : intent.getAction();
        try {
            if (STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (START.equals(action)) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    info = "RECORD_AUDIO not granted";stopSelf();return START_NOT_STICKY;
                }
                if (recording) {
                    info = "Stop and export the current test before reconfiguring devices.";
                    return START_NOT_STICKY;
                }
                AudioDevices.Choice c = AudioDevices.choose(this);
                String filename = new File(getFilesDir(), "fixture.wav").getAbsolutePath();
                String wav = new File(filename).isFile() ? filename : "";
                String speex = getApplicationInfo().nativeLibraryDir + "/libspeexdsp.so";
                info = EngineNative.start(wav, speex, c.input, c.output,
                        intent.getIntExtra("preset", 5),
                        intent.getBooleanExtra("stereo", false) ? 2 : 1);
                running = !info.startsWith("ERROR:");
                if (!running) { Log.e(TAG, info);stopSelf(); }
                else {
                    configure(intent);
                    EngineNative.setPlayback(0);
                    info += "\nMic preference: " + c.inputName + "\nOutput: " + c.outputName;
                    Log.i(TAG, info);
                }
            } else if (CONFIG.equals(action) && running) {
                configure(intent);
            } else if (PLAY.equals(action) && running) {
                EngineNative.setPlayback(intent.getIntExtra("play", 0));
            } else if (RECORD.equals(action) && running) {
                recording=EngineNative.record();
                info=recording ? "Recording synchronized mic/reference/processed PCM; 30s max"
                               : "Recording already active or not available";
                Log.i(TAG,info);
            } else if (EXPORT.equals(action) && running && recording) {
                recording=false;
                // Never write WAV files on the main thread or native AAudio callbacks.
                new Thread(() -> {
                    File dir=getExternalFilesDir(null);
                    if(dir==null){info="No external files directory";return;}
                    String prefix=new File(dir,"soundtest_"+System.currentTimeMillis()).getAbsolutePath();
                    info=EngineNative.export(prefix);
                    Log.i(TAG, info);
                },"wav-export").start();
            }
        } catch (Throwable e) {
            info="Sound engine failed: "+e;
            Log.e(TAG,info,e);
        }
        // Background mic restart is deliberately opt-in via visible Activity only.
        return START_NOT_STICKY;
    }
    private void configure(Intent intent) {
        EngineNative.config(intent.getIntExtra("engine", 1),
                intent.getIntExtra("ns", 1),
                intent.getBooleanExtra("agc", false),
                intent.getIntExtra("delay", 80));
    }
    @Override public void onDestroy() {
        try { EngineNative.stop(); } catch (Throwable t) {Log.e(TAG,"Stop failed",t);}
        running=false;recording=false;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
