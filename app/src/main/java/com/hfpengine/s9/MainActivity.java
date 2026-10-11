package com.hfpengine.s9;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.*;
public final class MainActivity extends Activity {
 private final Handler handler=new Handler();
 private Switch standby;
 private Spinner preset,route,processor,ns;
 private CheckBox stereo,agc,automatic;
 private EditText delay;
 private TextView metrics;
 private final int[] presets=CapturePresets.ids();
 private final Runnable update=new Runnable(){public void run(){refresh();handler.postDelayed(this,1000);}};
 private Spinner spinner(LinearLayout col,String label,String[] options,int selected){
  TextView t=new TextView(this);t.setText(label);col.addView(t);
  Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));s.setSelection(selected);col.addView(s);return s;
 }
 private CheckBox check(LinearLayout col,String label,boolean value){CheckBox c=new CheckBox(this);c.setText(label);c.setChecked(value);col.addView(c);return c;}
 private void button(LinearLayout col,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);col.addView(b);b.setOnClickListener(v->action.run());}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);SharedPreferences p=getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE);
  ScrollView scroll=new ScrollView(this);LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(24,24,24,32);scroll.addView(col);setContentView(scroll);
  TextView title=new TextView(this);title.setText("S9 HFP - Call engine v0.3.0");title.setTextSize(23);col.addView(title);
  TextView note=new TextView(this);note.setText("Root Bluetooth bridge. Standby keeps mic and speaker closed until SCO call audio arrives. Open Head Unit can stay open. Call routing is experimental; use a test call first.");col.addView(note);
  standby=new Switch(this);standby.setText("Enable boot standby / Bluetooth call engine");standby.setChecked(p.getBoolean("boot_standby",false));col.addView(standby);
  standby.setOnCheckedChangeListener((v,on)->{
   if(on&&EngineService.running&&!EngineService.callMode){standby.setChecked(false);Toast.makeText(this,"Stop Sound Engine Test before enabling standby",Toast.LENGTH_SHORT).show();return;}
   if(on&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){standby.setChecked(false);requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;}
   p.edit().putBoolean("boot_standby",on).apply();if(on)startForegroundService(new Intent(this,StandbyService.class));else stopService(new Intent(this,StandbyService.class));
  });
  route=spinner(col,"Microphone pathway",new String[]{"Prefer USB, otherwise internal","Internal microphone","USB microphone only (fail if absent)"},p.getInt("engine_mic_route",0));
  int selection=0;for(int i=0;i<presets.length;i++)if(presets[i]==p.getInt("engine_preset",5))selection=i;
  preset=spinner(col,"Microphone capture preset",CapturePresets.labels(),selection);
  stereo=check(col,"Probe stereo input (downmix to mono)",p.getBoolean("engine_stereo",false));
  processor=spinner(col,"Sound processor",new String[]{"Bypass / raw","WebRTC APM (AEC3 + NS)","SpeexDSP baseline (optional)"},p.getInt("engine_mode",1));
  ns=spinner(col,"Noise suppression",new String[]{"Off","Moderate","High"},p.getInt("engine_ns",1));
  agc=check(col,"Automatically raise speech level (AGC2)",p.getBoolean("engine_agc",false));
  int savedDelay=p.getInt("engine_delay",-1);automatic=check(col,"Automatic stream-delay hint + AEC3 internal alignment",savedDelay<0);
  delay=new EditText(this);delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);delay.setHint("Manual stream-delay hint, 0-500 ms");delay.setText(String.valueOf(savedDelay<0?80:savedDelay));delay.setEnabled(savedDelay>=0);col.addView(delay);
  automatic.setOnCheckedChangeListener((v,on)->delay.setEnabled(!on));
  TextView timing=new TextView(this);timing.setText("Auto uses audio timestamps when available; otherwise the 80 ms hint is unmeasured. AEC3 tracks echo alignment independently. Capture pathway changes apply on the next call or test restart.");col.addView(timing);
  button(col,"Save / apply sound settings",()->{
   int d=-1;if(!automatic.isChecked())try{d=Integer.parseInt(delay.getText().toString());if(d<0||d>500)throw new NumberFormatException();}catch(NumberFormatException e){Toast.makeText(this,"Enter a delay from 0 to 500 ms",Toast.LENGTH_SHORT).show();return;}
   Intent i=new Intent(this,EngineService.class).setAction(EngineService.CONFIG).putExtra("engine",processor.getSelectedItemPosition()).putExtra("ns",ns.getSelectedItemPosition()).putExtra("agc",agc.isChecked()).putExtra("delay",d).putExtra("preset",presets[preset.getSelectedItemPosition()]).putExtra("stereo",stereo.isChecked()).putExtra("mic_route",route.getSelectedItemPosition());
   EngineSettings.save(this,i);if(EngineService.running)startService(i);Toast.makeText(this,"Settings saved; capture route applies on next start",Toast.LENGTH_SHORT).show();
  });
  button(col,"Sound Engine Test",()->startActivity(new Intent(this,EngineActivity.class)));
  button(col,"Reset maximum peak meters",()->{if(EngineService.running)startService(new Intent(this,EngineService.class).setAction(EngineService.RESET_PEAKS));});
  metrics=new TextView(this);metrics.setTextSize(14);metrics.setTextIsSelectable(true);metrics.setPadding(0,24,0,0);col.addView(metrics);
 }
 private void refresh(){EngineService.refreshStats();metrics.setText("LIVE METRICS\n"+StandbyService.status+"\n"+(EngineService.running?EngineService.compactStatus:"Microphone / speaker: inactive")+"\n"+EngineService.info);}
 @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==1&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)standby.setChecked(true);}
 @Override protected void onResume(){super.onResume();
  SharedPreferences saved=getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE);
  route.setSelection(saved.getInt("engine_mic_route",0));
  for(int i=0;i<presets.length;i++)if(presets[i]==saved.getInt("engine_preset",5))preset.setSelection(i);
  processor.setSelection(saved.getInt("engine_mode",1));ns.setSelection(saved.getInt("engine_ns",1));
  agc.setChecked(saved.getBoolean("engine_agc",false));stereo.setChecked(saved.getBoolean("engine_stereo",false));
  int hint=saved.getInt("engine_delay",-1);automatic.setChecked(hint<0);delay.setText(String.valueOf(hint<0?80:hint));
  if(getSharedPreferences(AppSupport.PREFS,MODE_PRIVATE).getBoolean("boot_standby",false)&&!StandbyService.running&&!EngineService.running)
   startForegroundService(new Intent(this,StandbyService.class));
  handler.post(update);
 }
 @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
}
