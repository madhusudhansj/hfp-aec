package com.hfpengine.s9;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Runs against the phone's actual MediaStore without opening its microphone. */
public final class ExportVerification extends Instrumentation {
    private boolean migrate;
    private boolean live;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        migrate=arguments!=null&&"true".equals(arguments.getString("migrate"));
        live=arguments!=null&&"true".equals(arguments.getString("live"));
        start();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        String prefix = new File(context.getFilesDir(), "export_verification_" + System.nanoTime()).getAbsolutePath();
        List<Uri> published = null;
        int outcome=0;
        try {
            // A valid 44-byte PCM WAV header followed by 480 silent 16-bit samples.
            byte[] wav = new byte[1004];
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(996);
            b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
            b.putShort((short)1).putShort((short)1).putInt(48000).putInt(96000);
            b.putShort((short)2).putShort((short)16);
            b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(960);
            for(String suffix:RecordingExports.SUFFIXES){
                try(FileOutputStream out=new FileOutputStream(prefix+suffix)){
                    out.write(suffix.endsWith(".wav")?wav:"{\"synthetic_test\":true}".getBytes(StandardCharsets.UTF_8));
                }
            }
            published=RecordingExports.publish(context,prefix);
            check(published.size()==4,"Expected three WAVs and JSON");
            for(int i=0;i<published.size();i++){
                Uri uri=published.get(i);
                try(Cursor cursor=context.getContentResolver().query(uri,new String[]{
                        MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING},null,null,null)){
                    check(cursor!=null&&cursor.moveToFirst(),"Export missing from MediaStore");
                    check(RecordingExports.FOLDER.equals(cursor.getString(0)),"Wrong public Downloads folder");
                    check(cursor.getInt(1)==0,"Export still hidden as pending");
                }
                byte[] expected=i<3?wav:"{\"synthetic_test\":true}".getBytes(StandardCharsets.UTF_8);
                try(InputStream in=context.getContentResolver().openInputStream(uri)){
                    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                    byte[] chunk=new byte[1024];int n;
                    while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);
                    check(Arrays.equals(expected,bytes.toByteArray()),"Export content changed");
                }
            }
            // Missing metadata must fail validation and retain every remaining source.
            check(new File(prefix+"_metadata.json").delete(),"Cannot remove synthetic metadata");
            boolean rejected=false;
            try { RecordingExports.publish(context,prefix); }
            catch(java.io.IOException expected){rejected=true;}
            check(rejected,"Incomplete set was published");
            check(new File(prefix+"_mic_raw.wav").isFile(),"Failure lost original recording");
            check(EngineNative.stats().contains("Sound engine stopped"),"Unexpected native engine state");
            check(EngineNative.aecMonitor().contains("inactive"),"Inactive AEC3 monitor unavailable");
            String migration="";
            if(live){
                startActivitySync(new android.content.Intent(context,EngineActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                context.startForegroundService(new android.content.Intent(context,EngineService.class)
                        .setAction(EngineService.START).putExtra("delay",-1));
                try {
                    for(int i=0;i<50 && !EngineService.running;i++)Thread.sleep(100);
                    check(EngineService.running,"Live diagnostic engine failed: "+EngineService.info);
                    Thread.sleep(2200);
                    String stats=EngineNative.stats();
                    java.util.regex.Matcher processed=java.util.regex.Pattern.compile("processed ([0-9]+)").matcher(stats);
                    check(processed.find()&&Long.parseLong(processed.group(1))>0,"No paired audio processed: "+stats);
                    String monitor=EngineNative.aecMonitor();
                    check(monitor.contains("internal automatic delay tracking"),"AEC3 tracking mode missing");
                    check(!monitor.contains("waiting for processed audio"),"AEC3 statistics not sampled");
                    migration="\nLIVE DIAGNOSTIC (no recording):\n"+monitor;
                } finally {
                    context.stopService(new android.content.Intent(context,EngineService.class));
                }
            }
            if(migrate){
                android.content.Intent activity=new android.content.Intent(context,EngineActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySync(activity);
                EngineService.info="Migration verification pending";
                context.startForegroundService(new android.content.Intent(context,EngineService.class)
                        .setAction(EngineService.COPY_OLD));
                for(int i=0;i<100 && !EngineService.info.startsWith("Copied ")
                        && !EngineService.info.startsWith("Sound engine failed");i++)Thread.sleep(100);
                check(EngineService.info.startsWith("Copied "),EngineService.info);
                migration="\n"+EngineService.info;
            }
            result.putString("stream","PASS: four public Downloads files, pending flags cleared, byte-exact WAV/JSON, incomplete-set rejection, native library loads; synthetic files cleaned."+migration+"\n");
            outcome=-1;
        }catch(Throwable t){
            result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(t));
        }finally{
            if(published!=null)for(Uri uri:published)context.getContentResolver().delete(uri,null,null);
            RecordingExports.removeSources(prefix);
        }
        finish(outcome,result);
    }
}
