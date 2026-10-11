package com.hfpengine.s9;

import android.content.Context;
import android.content.Intent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Root helper lifecycle and authenticated loopback PCM transport. No idle audio streams. */
final class RootBridge implements AutoCloseable {
 private final Context context;
 private final String token=UUID.randomUUID().toString().replace("-","");
 private final String rootDir;
 private volatile boolean alive=true;
 private volatile ServerSocket server;
 private volatile Socket socket;
 private volatile Process process;
 private Thread rootThread,networkThread;
 private final boolean launchHelper;
 private volatile boolean ownsCall;
 RootBridge(Context c){this(c,true);}
 RootBridge(Context c,boolean helper){context=c;launchHelper=helper;rootDir="/data/local/tmp/s9-hfp-"+c.getPackageName()+"-"+token.substring(0,8);}
 void start(){
  networkThread=new Thread(this::serve,"sco-transport");networkThread.start();
  if(launchHelper){rootThread=new Thread(this::inject,"sco-root");rootThread.start();}
 }
 private File copyAsset(String name)throws IOException{
  File dir=new File(context.getFilesDir(),"bridge");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Bridge staging unavailable");
  File f=new File(dir,token.substring(0,8)+"-"+name);
  try(InputStream in=context.getAssets().open("bridge/"+name);OutputStream out=new FileOutputStream(f)){
   byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
  }return f;
 }
 private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
 private void inject(){
  try{
   File binary=copyAsset("frida-inject"),script=copyAsset("s9-apk-bridge.js"),boot=copyAsset("boot-standby.sh");
   String bootText=new String(java.nio.file.Files.readAllBytes(boot.toPath()),StandardCharsets.UTF_8).replace("@@PACKAGE@@",context.getPackageName());
   java.nio.file.Files.write(boot.toPath(),bootText.getBytes(StandardCharsets.UTF_8));
   String bootPath="/data/adb/service.d/s9-hfp-"+context.getPackageName()+".sh";
   Process installBoot=new ProcessBuilder("su","-c","umask 077; mkdir -p /data/adb/service.d && cp "+quote(boot.getAbsolutePath())+" "+quote(bootPath)+" && chmod 700 "+quote(bootPath)).redirectErrorStream(true).start();
   if(!installBoot.waitFor(15,TimeUnit.SECONDS)){installBoot.destroy();throw new IOException("Root boot setup timed out");}
   if(installBoot.exitValue()!=0)android.util.Log.w("HfpEngine","Root boot fallback installation failed");
   String text=new String(java.nio.file.Files.readAllBytes(script.toPath()),StandardCharsets.UTF_8).replace("@@TOKEN@@",token);
   java.nio.file.Files.write(script.toPath(),text.getBytes(StandardCharsets.UTF_8));
   // A private directory per standby instance prevents a late stop from killing a new helper.
   String setup="umask 077; mkdir -p "+quote(rootDir)+" && cp "+quote(binary.getAbsolutePath())+" "+quote(rootDir+"/injector")+" && cp "+quote(script.getAbsolutePath())+" "+quote(rootDir+"/bridge.js")+" && chmod 700 "+quote(rootDir+"/injector")+" || exit 1; ";
   while(alive){
    StandbyService.status="Requesting root / attaching Bluetooth helper...";
    String launch=setup+"pid=$(pidof com.android.bluetooth); [ -n \"$pid\" ] || exit 2; echo $$ > "+quote(rootDir+"/pid")+"; exec "+quote(rootDir+"/injector")+" -p \"$pid\" -s "+quote(rootDir+"/bridge.js");
    synchronized(this){if(!alive)break;process=new ProcessBuilder("su","-c",launch).redirectErrorStream(true).start();}
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){String line;
     while(alive&&(line=reader.readLine())!=null){
      android.util.Log.i("S9HciBridge",line);
      if(line.contains("Unsupported")||line.contains("Error")||line.contains("error")||line.contains("denied"))StandbyService.status="Bridge: "+line;
      else if(line.contains("APK bridge connected"))StandbyService.status="Bluetooth standby ready; microphone / speaker closed";
     }
    }
    int code=process.waitFor();if(alive){StandbyService.status="Root helper exited ("+code+"). Retrying; check root grant / Bluetooth build.";Thread.sleep(3000);}
   }
  }catch(Exception e){if(alive)StandbyService.status="Bridge unavailable: "+e.getMessage();}
  finally{if(!alive)cleanStaging();}
 }
 private void endCall(){
  if(ownsCall){ownsCall=false;context.stopService(new Intent(context,EngineService.class));}
 }
 private void serve(){
  try(ServerSocket listener=new ServerSocket()){
   listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),38629),1);server=listener;
   while(alive){
    try(Socket client=listener.accept()){
     socket=client;client.setTcpNoDelay(true);client.setSoTimeout(2000);
     DataInputStream in=new DataInputStream(client.getInputStream());byte[] auth=new byte[33];in.readFully(auth);
     if(!java.security.MessageDigest.isEqual(auth,(token+"\n").getBytes(StandardCharsets.US_ASCII)))continue;
     client.getOutputStream().write(6);
     client.setSoTimeout(250);StandbyService.status="Bluetooth standby ready; microphone / speaker closed";
     byte[] frame=new byte[248],pcm=new byte[240],silence=new byte[240];int offset=0,session=-1;long last=0;
     OutputStream out=client.getOutputStream();
     while(alive){
      try{
       int n=in.read(frame,offset,frame.length-offset);if(n<0)break;offset+=n;if(offset<frame.length)continue;offset=0;
       if(frame[0]!='S'||frame[1]!='9'||frame[3]!=1)throw new IOException("Invalid bridge framing");
       int current=ByteBuffer.wrap(frame,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
       if(frame[2]==2){endCall();session=-1;last=0;StandbyService.status="Call ended; microphone / speaker closing";continue;}
       if(frame[2]!=1)throw new IOException("Invalid bridge packet type");
       last=android.os.SystemClock.elapsedRealtime();
       if(current!=session){
        session=current;
        if(!EngineService.running||EngineService.callMode){ownsCall=true;context.startForegroundService(EngineSettings.intent(context,EngineService.START).putExtra("bridge",true));}
       }
       System.arraycopy(frame,8,pcm,0,240);
       byte[] response=ownsCall&&EngineService.running&&EngineService.callMode?EngineNative.bridgeExchange(pcm):silence;
       out.write(response==null?silence:response);
       StandbyService.status=ownsCall?(EngineService.running?"Bluetooth SCO call active (16 kHz mono)":"Call engine starting / failed: "+EngineService.info):"Call blocked: diagnostic engine owns audio";
      }catch(SocketTimeoutException timeout){if(last!=0&&android.os.SystemClock.elapsedRealtime()-last>1200){endCall();session=-1;last=0;StandbyService.status="SCO timeout; microphone / speaker closed";}}
     }
    }catch(IOException e){if(alive)StandbyService.status="Bridge waiting / transport: "+e.getMessage();}
    finally{socket=null;endCall();}
   }
  }catch(IOException e){if(alive)StandbyService.status="Bridge listener unavailable: "+e.getMessage();}
 }
 @Override public synchronized void close(){
  alive=false;endCall();
  try{if(socket!=null)socket.close();}catch(IOException ignored){}
  try{if(server!=null)server.close();}catch(IOException ignored){}
  if(!launchHelper)return;
  // Kill only our recorded injector after checking its executable path.
  new Thread(()->{
   try{
    String cmd="p=$(cat "+quote(rootDir+"/pid")+" 2>/dev/null); case \"$p\" in ''|*[!0-9]*) ;; *) if grep -q "+quote(rootDir+"/injector")+" /proc/\"$p\"/cmdline; then kill -TERM \"$p\"; fi;; esac; rm -f "+quote(rootDir+"/injector")+" "+quote(rootDir+"/bridge.js")+" "+quote(rootDir+"/pid")+"; rmdir "+quote(rootDir)+" 2>/dev/null";
    Process stop=new ProcessBuilder("su","-c",cmd).start();stop.waitFor(5,TimeUnit.SECONDS);
   }catch(Exception ignored){}
   if(process!=null)process.destroy();
   cleanStaging();
  },"sco-root-stop").start();
 }
 private void cleanStaging(){
  File dir=new File(context.getFilesDir(),"bridge");
  new File(dir,token.substring(0,8)+"-frida-inject").delete();
  new File(dir,token.substring(0,8)+"-s9-apk-bridge.js").delete();
  new File(dir,token.substring(0,8)+"-boot-standby.sh").delete();
 }
}
