package com.hfpengine.s9;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EngineService extends Service {
    static final String TAG = "S9SoundEngine";
    static final String START = "com.hfpengine.s9.ENGINE_START";
    static final String STOP = "com.hfpengine.s9.ENGINE_STOP";
    static final String PLAY = "com.hfpengine.s9.ENGINE_PLAY";
    static final String CONFIG = "com.hfpengine.s9.ENGINE_CONFIG";
    static final String RECORD = "com.hfpengine.s9.ENGINE_RECORD";
    static final String EXPORT = "com.hfpengine.s9.ENGINE_EXPORT";
    static final String COPY_OLD = "com.hfpengine.s9.ENGINE_COPY_OLD";
    static volatile boolean running = false;
    static volatile String info = "Not started";
    static volatile boolean recording = false;
    static volatile boolean exporting = false;
    static volatile String nativeStatus = "(inactive)";
    static volatile String aecMonitor = "AEC3 monitor inactive — arm the sound engine.";
    private final ExecutorService controls = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean statsQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private String pendingPrefix;

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        pendingPrefix = getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE)
                .getString("pending_export", null);
        startForeground(111, AppSupport.notification(this,
                "S9 Sound Engine — active", "Local audio processing and microphone test"));
        // Stop v0.1 to avoid two competing AAudio microphone owners.
        stopService(new Intent(this, AudioService.class));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        controls.execute(() -> handleCommand(intent));
        return START_NOT_STICKY;
    }
    private void handleCommand(Intent intent) {
        final String action = intent == null ? "" : intent.getAction();
        try {
            if (COPY_OLD.equals(action)) {
                File dir = getExternalFilesDir(null);
                File[] files = dir == null ? null : dir.listFiles((folder, name) ->
                        name.startsWith("soundtest_") && name.endsWith("_metadata.json"));
                int copied = 0;
                if (files != null) for (File metadata : files) {
                    String prefix = metadata.getAbsolutePath().replaceFirst("_metadata\\.json$", "");
                    String marker = "public_export_" + metadata.getName();
                    if (getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).getBoolean(marker, false)) continue;
                    RecordingExports.publish(this, prefix);
                    getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit().putBoolean(marker, true).apply();
                    ++copied;
                }
                info = "Copied " + copied + " earlier recording sets to Downloads → S9 HFP Tests. Originals retained.";
                if (!running) stopSelf();
                return;
            }
            if (STOP.equals(action)) {
                stopSelf();
                return;
            }
            if (START.equals(action)) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    info = "RECORD_AUDIO not granted";stopSelf();return;
                }
                if (recording || pendingPrefix != null) {
                    info = "Stop and export the current test before reconfiguring devices.";
                    return;
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
                if (recording || pendingPrefix != null) {
                    info = "Recording already active or awaiting export; stop and export first.";
                    return;
                }
                recording=EngineNative.record();
                info=recording ? "Recording synchronized mic/reference/processed PCM; 30s max"
                               : "Recording already active or not available";
                Log.i(TAG,info);
            } else if (EXPORT.equals(action) && (recording || pendingPrefix != null)) {
                exporting = true;
                try {
                    if (pendingPrefix == null) {
                        File dir = new File(getFilesDir(), "pending_exports");
                        if (!dir.isDirectory() && !dir.mkdirs())
                            throw new java.io.IOException("Cannot create export staging folder");
                        String prefix = new File(dir, "soundtest_" + System.currentTimeMillis()).getAbsolutePath();
                        String result = EngineNative.export(prefix);
                        if (!result.startsWith("Saved ")) {
                            info = result;
                            if (result.equals("No microphone audio captured")) recording = false;
                            return;
                        }
                        recording = false;
                        pendingPrefix = prefix;
                        getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit()
                                .putString("pending_export", prefix).apply();
                    }
                    RecordingExports.publish(this, pendingPrefix);
                    RecordingExports.removeSources(pendingPrefix);
                    pendingPrefix = null;
                    getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit()
                            .remove("pending_export").apply();
                    info = "Saved 3 WAVs + metadata. Files by Google → Downloads → S9 HFP Tests";
                    Log.i(TAG, info);
                } finally { exporting = false; }
            }
        } catch (Throwable e) {
            info="Sound engine failed: "+e;
            Log.e(TAG,info,e);
        }
        // Background mic restart is deliberately opt-in via visible Activity only.
    }
    private void configure(Intent intent) {
        EngineNative.config(intent.getIntExtra("engine", 1),
                intent.getIntExtra("ns", 1),
                intent.getBooleanExtra("agc", false),
                intent.getIntExtra("delay", -1));
    }
    void requestStats() {
        if (exporting || controls.isShutdown() || !statsQueued.compareAndSet(false, true)) return;
        try {
            controls.execute(() -> {
                try {
                    nativeStatus = EngineNative.stats();
                    aecMonitor = EngineNative.aecMonitor();
                }
                catch (Throwable t) { nativeStatus = t.toString(); }
                finally { statsQueued.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { statsQueued.set(false); }
    }
    static volatile EngineService active;
    static void refreshStats() {
        EngineService service = active;
        if (service != null) service.requestStats();
    }
    @Override public void onDestroy() {
        active = null;
        controls.execute(() -> {
            try { EngineNative.stop(); } catch (Throwable t) {Log.e(TAG,"Stop failed",t);}
            running=false; recording=false; exporting=false;
        });
        controls.shutdown();
        running=false;recording=false;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
