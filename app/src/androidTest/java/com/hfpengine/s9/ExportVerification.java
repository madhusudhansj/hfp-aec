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
    private boolean bridge;
    private boolean call;
    private boolean background;
    private boolean transport;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        migrate=arguments!=null&&"true".equals(arguments.getString("migrate"));
        live=arguments!=null&&"true".equals(arguments.getString("live"));
        bridge=arguments!=null&&"true".equals(arguments.getString("bridge"));
        call=arguments!=null&&"true".equals(arguments.getString("call"));
        background=arguments!=null&&"true".equals(arguments.getString("background"));
        if(call)live=true;
        transport=arguments!=null&&"true".equals(arguments.getString("transport"));
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
            check(EngineNative.verifyPeakMeters(),"PCM peak, RMS, clipping, hold, or reset check failed");
            check(EngineNative.verifyScoResampler(),"SCO conversion passband / alias rejection failed");
            check(Arrays.equals(EngineNative.bridgeExchange(new byte[240]),new byte[240]),"Inactive call uplink must be silent");
            check(EngineNative.bridgeExchange(new byte[239])==null,"Malformed SCO payload accepted");
            check(Arrays.equals(CapturePresets.ids(),new int[]{5,1,6,7,9,10}),"S9 capture presets incomplete");
            String migration="";
            if(transport){
                startActivitySync(new android.content.Intent(context,EngineActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                RootBridge adapter=new RootBridge(context,false);
                adapter.start();
                try{
                    java.net.Socket client=null;
                    for(int retry=0;retry<30 && client==null;retry++)try{client=new java.net.Socket("127.0.0.1",38629);}catch(java.io.IOException wait){Thread.sleep(100);}
                    check(client!=null,"Transport listener did not start");
                    try(java.net.Socket connection=client){
                        connection.setSoTimeout(3000);connection.setTcpNoDelay(true);
                        java.lang.reflect.Field field=RootBridge.class.getDeclaredField("token");field.setAccessible(true);
                        java.io.OutputStream output=connection.getOutputStream();
                        output.write((field.get(adapter)+"\n").getBytes(StandardCharsets.US_ASCII));
                        java.io.DataInputStream input=new java.io.DataInputStream(connection.getInputStream());
                        check(input.read()==6,"Transport authentication failed");
                        byte[] frame=new byte[248],reply=new byte[240];frame[0]='S';frame[1]='9';frame[3]=1;
                        for(int session=1;session<=2;session++){
                            frame[2]=1;frame[4]=(byte)session;
                            long started=System.nanoTime();
                            for(int packet=0;packet<150;packet++){
                                output.write(frame);input.readFully(reply);
                                long wait=started+(packet+1)*7500000L-System.nanoTime();
                                if(wait>0)Thread.sleep(wait/1000000L,(int)(wait%1000000L));
                            }
                            check(EngineService.running && EngineService.callMode,"Transport did not start call engine: "+EngineService.info);
                            check(EngineNative.stats().contains("AAudio error 0"),"Call audio stream error");
                            frame[2]=2;output.write(frame);
                            for(int wait=0;wait<50 && EngineService.running;wait++)Thread.sleep(100);
                            check(!EngineService.running,"Call end did not stop audio");
                            check(EngineNative.stats().contains("Sound engine stopped"),"Native streams remained open after call end");
                        }
                        frame[3]=2;output.write(frame);
                        check(input.read()==-1,"Malformed protocol version was accepted");
                    }
                    migration=" Authenticated PCM transport, two call start/stop cycles, closed idle streams, malformed protocol rejection passed.";
                }finally{adapter.close();context.stopService(new android.content.Intent(context,EngineService.class));}
            }
            if(bridge){
                android.content.Intent open=new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySync(open);
                context.startForegroundService(new android.content.Intent(context,StandbyService.class));
                try{
                    for(int attempt=0;attempt<120 && !StandbyService.status.contains("standby ready");attempt++)Thread.sleep(500);
                    check(StandbyService.status.contains("standby ready"),"Root bridge did not connect: "+StandbyService.status);
                    check(!EngineService.running && EngineNative.stats().contains("Sound engine stopped"),"Standby opened audio streams");
                    migration=" Root bridge attached; idle microphone and speaker stayed closed.";
                }finally{context.stopService(new android.content.Intent(context,StandbyService.class));}
            }
            if(live){
                startActivitySync(new android.content.Intent(context,EngineActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                if(background){
                    try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("input keyevent 3")){
                        Thread.sleep(1200);
                    }
                }
                context.startForegroundService(new android.content.Intent(context,EngineService.class)
                        .setAction(EngineService.START).putExtra("delay",-1).putExtra("bridge",call));
                try {
                    for(int i=0;i<50 && !EngineService.running;i++)Thread.sleep(100);
                    check(EngineService.running,"Live diagnostic engine failed: "+EngineService.info);
                    if(call){
                        byte[] silent=new byte[240];
                        for(int frame=0;frame<300;frame++){
                            check(EngineNative.bridgeExchange(silent).length==240,"SCO output framing failed");
                            Thread.sleep(frame%2==0?7:8);
                        }
                    }else Thread.sleep(2200);
                    String stats=EngineNative.stats();
                    java.util.regex.Matcher processed=java.util.regex.Pattern.compile("processed ([0-9]+)").matcher(stats);
                    check(processed.find()&&Long.parseLong(processed.group(1))>0,"No paired audio processed: "+stats);
                    check(stats.contains("AAudio error 0"),"Audio stream error: "+stats);
                    String monitor=EngineNative.aecMonitor();
                    check(monitor.contains("internal automatic delay tracking"),"AEC3 tracking mode missing");
                    check(!monitor.contains("waiting for processed audio"),"AEC3 statistics not sampled");
                    migration="\nLIVE DIAGNOSTIC (no recording; call="+call+", background="+background+"):\n"+monitor+"\n"+EngineNative.compactStats();
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
            result.putString("stream","PASS: public Downloads, byte-exact exports, incomplete-set rejection, native monitor, six capture presets, PCM peak/RMS/clipping boundaries, maximum peak hold/reset, SCO FIR passband/alias rejection and packet validation; synthetic files cleaned."+migration+"\n");
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
