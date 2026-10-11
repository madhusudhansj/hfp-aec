package com.hfpengine.s9;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int MIC_REQUEST = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 900); }
    };
    private TextView status;
    private Button standbyButton, toneButton;
    private Spinner presetSpinner;
    private final int[] presets = CapturePresets.ids();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setPadding(28, 28, 28, 28);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText("S9 HFP Audio Engine — v0.2");
        heading.setTextSize(22);
        heading.setTypeface(null, Typeface.BOLD);
        col.addView(heading);
        TextView note = new TextView(this);
        note.setText("Stage 1: foreground services + real AAudio microphone capture and output route diagnostics. No HCI injection or AEC yet. Test tone is quiet; do not hold speaker next to your ear.");
        note.setPadding(0, 12, 0, 12);
        col.addView(note);

        standbyButton = button(col, "Enable boot standby", () -> {
            boolean enabled = !getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE)
                    .getBoolean("boot_standby", false);
            getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("boot_standby", enabled).apply();
            if (enabled && !AudioService.running) startForegroundService(new Intent(this, StandbyService.class));
            if (!enabled) stopService(new Intent(this, StandbyService.class));
            refresh();
        });
        TextView presetText = new TextView(this);
        presetText.setText("Internal mic preset (USB mic may ignore it):");
        col.addView(presetText);
        presetSpinner = new Spinner(this);
        String[] labels = CapturePresets.labels();
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int selected = getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).getInt("preset", 5);
        for (int i=0; i<presets.length; ++i) if (presets[i] == selected) presetSpinner.setSelection(i);
        col.addView(presetSpinner);

        button(col, "Open v0.2 WebRTC AEC3 sound-engine test", () -> {
            startActivity(new Intent(this, EngineActivity.class));
        });
        button(col, "Arm native audio (foreground)", this::armAudio);
        button(col, "Apply selected mic preset / route", () -> {
            int preset = presets[presetSpinner.getSelectedItemPosition()];
            getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit().putInt("preset",preset).apply();
            if (AudioService.running) command(AudioService.ACTION_RESTART);
            refresh();
        });
        toneButton = button(col, "Play quiet reference tone", () -> {
            if (!AudioService.running) { toast("Arm audio first"); return; }
            boolean on = !AudioService.tone;
            Intent intent = new Intent(this, AudioService.class).setAction(AudioService.ACTION_TONE);
            intent.putExtra("enabled", on);
            startService(intent);
            refresh();
        });
        button(col, "Stop microphone / audio engine", () -> {
            stopService(new Intent(this, AudioService.class));
            if (getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).getBoolean("boot_standby", false))
                startForegroundService(new Intent(this, StandbyService.class));
            refresh();
        });
        button(col, "Refresh devices and levels", this::refresh);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        status.setPadding(0, 20, 0, 40);
        col.addView(status);
        refresh();
    }
    private Button button(LinearLayout target, String label, Runnable r) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        target.addView(b);
        b.setOnClickListener(v -> r.run());
        return b;
    }
    private void command(String action) { startService(new Intent(this, AudioService.class).setAction(action)); }
    private void armAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).edit()
                .putInt("preset", presets[presetSpinner.getSelectedItemPosition()]).apply();
        startForegroundService(new Intent(this, AudioService.class));
    }
    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(req, permissions, grants);
        if (req == MIC_REQUEST && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            armAudio();
        else toast("Microphone permission required to arm engine");
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void refresh() {
        if (status == null) return;
        boolean boot = getSharedPreferences(AppSupport.PREFS, MODE_PRIVATE).getBoolean("boot_standby",false);
        standbyButton.setText(boot ? "Disable boot standby" : "Enable boot standby");
        toneButton.setText(AudioService.tone ? "Stop reference tone" : "Play quiet reference tone");
        String nativeState = "(not armed)";
        if (AudioService.running) {
            try { nativeState = NativeAudio.stats(); } catch (Throwable ex) { nativeState = ex.toString(); }
        }
        status.setText("BOOT STANDBY ENABLED: " + boot
                + "\nSTANDBY RUNNING: " + StandbyService.running
                + "\nAUDIO ARMED: " + AudioService.running
                + "\nTEST TONE: " + AudioService.tone
                + "\n\n" + AudioService.lastStatus
                + "\n\n" + nativeState
                + "\n\n" + AudioDevices.describe(this)
                + "\nStage 2: WebRTC APM; Stage 3: privileged HCI bridge.\n");
    }
    @Override protected void onResume() { super.onResume(); handler.post(update); }
    @Override protected void onPause() { handler.removeCallbacks(update); super.onPause(); }
}
