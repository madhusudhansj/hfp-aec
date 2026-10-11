package com.hfpengine.s9;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
final class EngineSettings {
 static Intent intent(Context c,String action){
  SharedPreferences p=c.getSharedPreferences(AppSupport.PREFS,Context.MODE_PRIVATE);
  return new Intent(c,EngineService.class).setAction(action).putExtra("engine",p.getInt("engine_mode",1)).putExtra("ns",p.getInt("engine_ns",1)).putExtra("agc",p.getBoolean("engine_agc",false)).putExtra("delay",p.getInt("engine_delay",-1)).putExtra("preset",p.getInt("engine_preset",5)).putExtra("stereo",p.getBoolean("engine_stereo",false)).putExtra("mic_route",p.getInt("engine_mic_route",0));
 }
 static void save(Context c,Intent i){
  c.getSharedPreferences(AppSupport.PREFS,Context.MODE_PRIVATE).edit().putInt("engine_mode",i.getIntExtra("engine",1)).putInt("engine_ns",i.getIntExtra("ns",1)).putBoolean("engine_agc",i.getBooleanExtra("agc",false)).putInt("engine_delay",i.getIntExtra("delay",-1)).putInt("engine_preset",i.getIntExtra("preset",5)).putBoolean("engine_stereo",i.getBooleanExtra("stereo",false)).putInt("engine_mic_route",i.getIntExtra("mic_route",0)).apply();
 }
}
