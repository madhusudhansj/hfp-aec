package com.hfpengine.s9;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class EngineActivity extends Activity {
    private static final int REQ_MIC=230, REQ_FILE=231;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,monitor;
    private Spinner preset,engine,ns;
    private CheckBox stereo,agc,autoDelay;
    private EditText delay;
    private final int[] presets=CapturePresets.ids();
    private final Runnable refresh=new Runnable(){
        @Override public void run(){drawStatus();handler.postDelayed(this,1000);}
    };
    private Button button(LinearLayout l,String name,Runnable action){
        Button b=new Button(this);b.setAllCaps(false);b.setText(name);
        l.addView(b);b.setOnClickListener(v->action.run());return b;
    }
    private Spinner spinner(LinearLayout col,String title,String[] options){
        TextView label=new TextView(this);label.setText(title);col.addView(label);
        Spinner s=new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
        col.addView(s);return s;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView sv=new ScrollView(this);
        LinearLayout col=new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24,24,24,24);sv.addView(col);setContentView(sv);
        TextView head=new TextView(this);
        head.setText("S9 HFP — Real-time Sound Engine v0.3.0");
        head.setTextSize(22);col.addView(head);
        TextView explain=new TextView(this);
        explain.setText("Diagnostic path: real mic and speaker plus WebRTC AEC3. Arming this test pauses Bluetooth standby. Load a speech WAV, arm the test, then listen to exported raw and processed WAVs. Re-enable standby on the main page when finished.");
        col.addView(explain);
        monitor=new TextView(this);
        monitor.setTextIsSelectable(true);
        monitor.setTextSize(14);
        monitor.setPadding(0,16,0,16);
        col.addView(monitor);
        preset=spinner(col,"Microphone capture preset",CapturePresets.labels());
        int savedPreset=getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getInt("engine_preset",5);
        for(int i=0;i<presets.length;i++)if(presets[i]==savedPreset)preset.setSelection(i);
        TextView presetHelp=new TextView(this);
        presetHelp.setText("Tap Arm / restart after changing the capture preset. Unprocessed may be quieter; Voice communication may add Android/vendor processing. Presets do not guarantee a particular physical mic. Stereo meters measure the downmixed mono signal.");
        col.addView(presetHelp);
        stereo=new CheckBox(this);stereo.setText("Probe two-channel microphone input (downmixed for AEC)");
        col.addView(stereo);
        engine=spinner(col,"Sound processor",new String[]{"Bypass/raw","WebRTC APM (AEC3 + NS)","SpeexDSP baseline (optional)"});
        engine.setSelection(getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getInt("engine_mode",1));
        ns=spinner(col,"Noise suppression",new String[]{"Off","Moderate","High"});
        ns.setSelection(getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getInt("engine_ns",1));
        agc=new CheckBox(this);agc.setText("Automatically raise speech level (WebRTC AGC2)");col.addView(agc);
        agc.setChecked(getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getBoolean("engine_agc",false));
        stereo.setChecked(getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getBoolean("engine_stereo",false));
        TextView gainHelp=new TextView(this);
        gainHelp.setText("AGC2 adjusts digital gain after echo/noise removal. Raw mic stays unchanged. Enable for quiet speech; watch processed level and clipping below.");
        col.addView(gainHelp);
        autoDelay=new CheckBox(this);autoDelay.setText("Estimate stream delay from audio timestamps");
        int savedDelay=getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getInt("engine_delay",-1);
        autoDelay.setChecked(savedDelay<0);col.addView(autoDelay);
        delay=new EditText(this);delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        delay.setText(String.valueOf(savedDelay<0?80:savedDelay));col.addView(delay);
        delay.setEnabled(savedDelay>=0);
        autoDelay.setOnCheckedChangeListener((v,checked)->delay.setEnabled(!checked));
        TextView dLabel=new TextView(this);
        dLabel.setText("External stream-delay hint (0–500 ms). Auto tries hardware timestamps, with an 80 ms fallback. AEC3 independently tracks echo alignment; this value does not lock it to 80 ms. Timestamp errors appear in the monitor.");
        col.addView(dLabel);
        button(col,"Import speech WAV (PCM16 mono/stereo)",this::pickWav);
        button(col,"Arm / restart real-time engine",this::arm);
        button(col,"Apply AEC / noise settings",()->send(EngineService.CONFIG,-1));
        button(col,"Reset maximum peak meters",()->send(EngineService.RESET_PEAKS,-1));
        button(col,"Play speech fixture through speaker",()->send(EngineService.PLAY,1));
        button(col,"Play diagnostic tone",()->send(EngineService.PLAY,2));
        button(col,"Stop speaker playback",()->send(EngineService.PLAY,0));
        button(col,"Start synchronized recording (max 30s)",()->send(EngineService.RECORD,-1));
        button(col,"Stop / retry export to Downloads",()->send(EngineService.EXPORT,-1));
        TextView exportHelp=new TextView(this);
        exportHelp.setText("Find all 3 WAVs and metadata in Files by Google → Downloads → S9 HFP Tests. Compare raw and processed speech at the same playback volume.");
        col.addView(exportHelp);
        button(col,"Copy earlier exports to Downloads",()->send(EngineService.COPY_OLD,-1));
        button(col,"Stop sound engine",()->stopService(new Intent(this,EngineService.class)));
        status=new TextView(this);status.setTextIsSelectable(true);status.setTextSize(12);
        col.addView(status);
        drawStatus();
    }
    private int delayMs(){
        if(autoDelay.isChecked())return -1;
        try{return Math.max(0,Math.min(500,Integer.parseInt(delay.getText().toString())));}
        catch(Exception e){return 80;}
    }
    private void send(String action,int play){
        if(!EngineService.running && !EngineService.START.equals(action)
                && !EngineService.COPY_OLD.equals(action) && !EngineService.EXPORT.equals(action)){
            Toast.makeText(this,"Arm sound engine first",Toast.LENGTH_SHORT).show();return;
        }
        Intent i=new Intent(this,EngineService.class).setAction(action)
                .putExtra("engine",engine.getSelectedItemPosition())
                .putExtra("ns",ns.getSelectedItemPosition())
                .putExtra("agc",agc.isChecked())
                .putExtra("delay",delayMs())
                .putExtra("preset",presets[preset.getSelectedItemPosition()])
                .putExtra("stereo",stereo.isChecked());
        if(play>=0)i.putExtra("play",play);
        i.putExtra("mic_route",getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getInt("engine_mic_route",0));
        if(EngineService.START.equals(action)||EngineService.CONFIG.equals(action))EngineSettings.save(this,i);
        if(EngineService.START.equals(action) || EngineService.COPY_OLD.equals(action)
                || EngineService.EXPORT.equals(action))startForegroundService(i);
        else startService(i);
    }
    private void arm(){
        if(EngineService.callMode){Toast.makeText(this,"End the Bluetooth call before starting diagnostics",Toast.LENGTH_SHORT).show();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
        }
        // Stop old diagnostic capture before opening another microphone stream.
        stopService(new Intent(this,AudioService.class));
        stopService(new Intent(this,StandbyService.class));
        getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).edit()
                .putInt("engine_preset",presets[preset.getSelectedItemPosition()]).apply();
        send(EngineService.START,-1);
    }
    private void pickWav(){
        Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("*/*");
        in.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/wave","application/octet-stream"});
        startActivityForResult(in,REQ_FILE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=REQ_FILE||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();
        // Bounded copy off main thread. Restart engine after a new fixture is imported.
        new Thread(()->{
            File dest=new File(getFilesDir(),"fixture.wav");
            boolean ok=false;
            try(InputStream in=getContentResolver().openInputStream(uri);
                FileOutputStream out=new FileOutputStream(dest)){
                if(in==null)throw new IllegalStateException("No input stream");
                byte[] buf=new byte[8192];int total=0,n;
                while((n=in.read(buf))!=-1){
                    total+=n;
                    if(total>12000000)throw new IllegalStateException("WAV exceeds 12 MB");
                    out.write(buf,0,n);
                }
                ok=true;
            }catch(Exception e){android.util.Log.e("S9SoundEngine","Import WAV failed",e);dest.delete();}
            final boolean success=ok;
            runOnUiThread(()->Toast.makeText(this,
                    success?"WAV copied; tap Arm / restart to load it":"WAV import failed",
                    Toast.LENGTH_LONG).show());
        },"wav-import").start();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){
        super.onRequestPermissionsResult(req,permissions,results);
        if(req==REQ_MIC&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)arm();
    }
    private void drawStatus(){
        if(status==null)return;
        monitor.setText(EngineService.running?EngineService.aecMonitor:
                "AEC3 monitor inactive — arm the sound engine.");
        String nativeStats="(inactive)";
        if(EngineService.running){
            EngineService.refreshStats();
            nativeStats=EngineService.nativeStatus;
        }
        status.setText("ACTIVE: "+EngineService.running+" RECORDING: "+EngineService.recording+
                "\n"+EngineService.info+"\n\n"+nativeStats+"\n\n"+AudioDevices.describe(this));
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
