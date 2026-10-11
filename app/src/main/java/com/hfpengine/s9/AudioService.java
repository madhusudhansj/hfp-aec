package com.hfpengine.s9;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class AudioService extends Service {
    private static final String TAG = "HfpEngine";
    public static final String ACTION_TONE = "com.hfpengine.s9.TONE";
    public static final String ACTION_STOP = "com.hfpengine.s9.STOP_AUDIO";
    public static final String ACTION_RESTART = "com.hfpengine.s9.RESTART_AUDIO";
    public static volatile boolean running = false;
    public static volatile boolean tone = false;
    public static volatile String lastStatus = "Not armed";

    private AudioManager audioManager;
    private Handler handler;
    private final Runnable reselect = this::restartNative;
    private AudioDeviceCallback routingCallback;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        // Start the FGS promptly; the mic permission was requested in the visible activity.
        startForeground(102, AppSupport.notification(this,
                "S9 HFP Engine — armed", "Microphone diagnostic active; no HCI bridge yet"));
        stopService(new Intent(this, StandbyService.class));
        routingCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) { scheduleReselect(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) { scheduleReselect(); }
        };
        audioManager.registerAudioDeviceCallback(routingCallback, handler);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastStatus = "RECORD_AUDIO not granted. Open app and grant permission.";
            stopSelf();
            return;
        }
        restartNative();
        running = true;
    }

    private void scheduleReselect() {
        // A USB DAC often reports several changes in quick succession.
        handler.removeCallbacks(reselect);
        handler.postDelayed(reselect, 700);
    }
    private void restartNative() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        AudioDevices.Choice c = AudioDevices.choose(this);
        int preset = getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).getInt("preset", 5);
        try {
            NativeAudio.stop();
            lastStatus = NativeAudio.start(c.input, c.output, preset)
                    + "\nPreferred input: " + c.inputName
                    + "\nPreferred output: " + c.outputName;
            NativeAudio.setTestTone(tone);
            Log.i(TAG, lastStatus);
        } catch (Throwable ex) {
            lastStatus = "Native start failed: " + ex;
            Log.e(TAG, lastStatus, ex);
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TONE.equals(action)) {
            tone = intent.getBooleanExtra("enabled", false);
            try { NativeAudio.setTestTone(tone); } catch (Throwable ex) { Log.e(TAG,"Tone failed",ex); }
        } else if (ACTION_RESTART.equals(action)) {
            restartNative();
        }
        // Do not request automatic recreation of a mic FGS from the background after process death.
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        running = false;
        tone = false;
        handler.removeCallbacks(reselect);
        if (routingCallback != null) audioManager.unregisterAudioDeviceCallback(routingCallback);
        try { NativeAudio.stop(); } catch (Throwable ex) { Log.e(TAG,"Native stop failed",ex); }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
