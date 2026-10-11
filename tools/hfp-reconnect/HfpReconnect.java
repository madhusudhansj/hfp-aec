package com.hfpengine.s9.root;
import android.bluetooth.*;
import java.lang.reflect.*;
public final class HfpReconnect {
 public static void main(String[] args) throws Exception {
  if(args.length!=2 || !args[0].matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))throw new IllegalArgumentException("paired address and check/connect required");
  BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
  BluetoothDevice device=adapter.getRemoteDevice(args[0]);
  Field field=BluetoothAdapter.class.getDeclaredField("mService");field.setAccessible(true);Object service=field.get(adapter);
  if(service==null){System.out.println("Bluetooth unavailable");return;}
  System.out.println("bond="+device.getBondState()+" HFP state="+adapter.getProfileConnectionState(16));
  if(adapter.getProfileConnectionState(16)==BluetoothProfile.STATE_CONNECTED){System.out.println("HFP connected");return;}
  for(Method method:service.getClass().getMethods())if(method.getName().equals("connectAllEnabledProfiles")){
   System.out.println("Available: "+method);
   if(!args[1].equals("connect"))return;
   if(device.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IllegalStateException("Device not paired");
   Object[] values=new Object[method.getParameterCount()];
   for(int i=0;i<values.length;i++){
    Class<?> type=method.getParameterTypes()[i];
    if(type==BluetoothDevice.class)values[i]=device;
    else if(type.getName().equals("android.content.AttributionSource")){
     Class<?> builder=Class.forName("android.content.AttributionSource$Builder");Object b=builder.getConstructor(int.class).newInstance(0);values[i]=builder.getMethod("build").invoke(b);
    }else throw new IllegalStateException("Unsupported argument "+type);
   }
   System.out.println("Connect requested="+method.invoke(service,values));return;
  }
  throw new IllegalStateException("ROM lacks profile connect API");
 }
}
