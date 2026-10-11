// Root-only Frida adapter for the verified S9+ Bluetooth build. No audio DSP here.
// Protocol: S9, kind (1=PCM, 2=end), version 1, LE session id, 240 PCM bytes.
'use strict';
// Pure command-format guard; transport payload length alone cannot identify PCM.
function supportedFormat(h){
 return h.add(31).readU32()===32000 && h.add(35).readU32()===32000
  && h.add(39).readU8()===4 && h.add(44).readU8()===4
  && h.add(49).readU16()===16 && h.add(51).readU16()===16
  && h.add(53).readU8()===2 && h.add(54).readU8()===2
  && h.add(55).readU8()===0 && h.add(56).readU8()===0;
}
const bt=Process.findModuleByName('libbluetooth.so');
if(!bt||!bt.path.endsWith('/system/lib64/libbluetooth.so'))throw Error('Unexpected Bluetooth module');
const id=Array.from(new Uint8Array(bt.base.add(0x298).readByteArray(16)),b=>b.toString(16).padStart(2,'0')).join('');
if(id!=='8775751177d49ddd74037f7341fbafc2')throw Error('Unsupported Bluetooth build: '+id);
const symbols=bt.enumerateSymbols();
const command=symbols.find(s=>s.name==='_Z17btu_hcif_send_cmdhP6BT_HDR');
if(!command)throw Error('HCI command symbol unavailable');
const state=Memory.alloc(20000);
const mod=new CModule(`
#include <glib.h>
#include <stdint.h>
#include <string.h>
extern uint8_t area[];
#define CAP 8
struct S { GMutex lock; uint32_t dh,dt,uh,ut,session; uint8_t down[CAP][248],up[CAP][240]; };
#define S ((struct S*)area)
void init(void){memset(S,0,sizeof(*S));g_mutex_init(&S->lock);}
void begin(uint32_t session){g_mutex_lock(&S->lock);S->dh=S->dt=S->uh=S->ut=0;S->session=session;g_mutex_unlock(&S->lock);}
void receive(const uint8_t *pcm){g_mutex_lock(&S->lock);
 if(S->dh-S->dt>=CAP)S->dt++;
 uint8_t *p=S->down[S->dh++%CAP];p[0]='S';p[1]='9';p[2]=1;p[3]=1;
 for(int i=0;i<4;i++)p[4+i]=(S->session>>(i*8))&255;
 memcpy(p+8,pcm,240);g_mutex_unlock(&S->lock);}
int pull(uint8_t *dst){g_mutex_lock(&S->lock);int n=0;
 if(S->dh!=S->dt){memcpy(dst,S->down[S->dt++%CAP],248);n=248;}g_mutex_unlock(&S->lock);return n;}
void push_up(const uint8_t *pcm){g_mutex_lock(&S->lock);
 if(S->uh-S->ut>=4)S->ut=S->uh-3;
 memcpy(S->up[S->uh++%CAP],pcm,240);g_mutex_unlock(&S->lock);}
void pop_up(uint8_t *dst){g_mutex_lock(&S->lock);
 if(S->uh!=S->ut)memcpy(dst,S->up[S->ut++%CAP],240);else memset(dst,0,240);
 g_mutex_unlock(&S->lock);}
`,{area:state});
const init=new NativeFunction(mod.init,'void',[]),begin=new NativeFunction(mod.begin,'void',['uint']);
const receive=new NativeFunction(mod.receive,'void',['pointer']),pull=new NativeFunction(mod.pull,'int',['pointer']);
const pushUp=new NativeFunction(mod.push_up,'void',['pointer']),popUp=new NativeFunction(mod.pop_up,'void',['pointer']);
const alloc=new NativeFunction(bt.base.add(0x3f0598),'pointer',['ulong']);
const transmit=new NativeFunction(bt.base.add(0x300c54),'void',['uint','pointer']);
init();
let connection=null,session=0,armed=false,lastRx=0,handle=-1,writing=false,endPending=false;
function finish(){if(armed||lastRx){armed=false;lastRx=0;handle=-1;begin(session);endPending=true;}}
Interceptor.attach(command.address,{onEnter(args){
 const p=args[1];if(p.isNull()||!connection)return;
 const len=p.add(2).readU16(),off=p.add(4).readU16();if(len!==66||off>16)return;
 const h=p.add(8+off);if(h.readU16()!==0x043e||h.add(2).readU8()!==63)return;
 if(h.add(57).readU8()!==1||h.add(58).readU8()!==1)return;
 if(!supportedFormat(h)){console.log('Unsupported SCO PCM format; leaving call route unchanged');return;}
 // Only reroute future calls while the APK transport is connected.
 session=(session+1)>>>0;begin(session);armed=true;lastRx=0;handle=-1;endPending=false;
 h.add(57).writeU8(0);h.add(58).writeU8(0);
 console.log('Routed SCO session '+session);
}});
Interceptor.attach(bt.base.add(0x2ff3bc),{onEnter(args){
 if(!connection||!armed)return;
 const p=args[0];if(p.isNull()||p.readU16()!==0x1200)return;
 const len=p.add(2).readU16(),off=p.add(4).readU16();if(len!==243||off>32)return;
 const h=p.add(8+off);if(h.add(2).readU8()!==240)return;
 const current=h.readU16()&0xfff;if(handle!==-1&&handle!==current)return;
 handle=current;lastRx=Date.now();receive(h.add(3));
 const msg=alloc(251);if(msg.isNull())return;
 msg.writeU16(0x2200);msg.add(2).writeU16(243);msg.add(4).writeU16(0);msg.add(6).writeU16(0);
 const tx=msg.add(8);tx.writeU16(handle);tx.add(2).writeU8(240);popUp(tx.add(3));transmit(0x2200,msg);
}});
// Prompt teardown where this build exports the HCI event dispatcher; timeout is a backup.
const events=symbols.find(s=>s.name==='_Z22btu_hcif_process_eventhP6BT_HDR');
if(events)Interceptor.attach(events.address,{onEnter(args){
 const p=args[1];if(p.isNull()||handle<0)return;
 const len=p.add(2).readU16(),off=p.add(4).readU16();if(len<6||off>32)return;
 const h=p.add(8+off);if(h.readU8()===5&&h.add(1).readU8()===4&&(h.add(3).readU16()&0xfff)===handle)finish();
}});
const temp=Memory.alloc(248),up=Memory.alloc(240);
setInterval(async()=>{
 if(lastRx&&Date.now()-lastRx>1000)finish();
 if(!connection||writing)return;writing=true;const c=connection;
 try{
  if(endPending){const b=new Uint8Array(248);b[0]=83;b[1]=57;b[2]=2;b[3]=1;new DataView(b.buffer).setUint32(4,session,true);await c.output.writeAll(b.buffer);endPending=false;}
  for(let i=0;i<8&&connection===c;i++){if(!pull(temp))break;await c.output.writeAll(temp.readByteArray(248));}
 }catch(e){finish();connection=null;try{await c.close();}catch(_){} }
 finally{writing=false;}
},2);
(async()=>{while(true){let c;
 try{
  c=await Socket.connect({family:'ipv4',host:'127.0.0.1',port:38629});await c.setNoDelay(true);
  const token='@@TOKEN@@\n';await c.output.writeAll(Uint8Array.from(token,x=>x.charCodeAt(0)).buffer);
  const ack=new Uint8Array(await c.input.read(1));if(ack.length!==1||ack[0]!==6)throw Error('APK authentication rejected');
  connection=c;console.log('APK bridge connected; awaiting a new call');
  let pending=new Uint8Array(0);
  while(connection===c){const data=await c.input.read(4096);if(data.byteLength===0)break;
   const joined=new Uint8Array(pending.length+data.byteLength);joined.set(pending);joined.set(new Uint8Array(data),pending.length);
   let offset=0;while(offset+240<=joined.length){up.writeByteArray(joined.slice(offset,offset+240));pushUp(up);offset+=240;}
   pending=joined.slice(offset);
  }
 }catch(e){console.log('APK transport: '+e);}
 finally{finish();if(connection===c)connection=null;if(c)try{await c.close();}catch(_){} }
 await new Promise(r=>setTimeout(r,1500));
}})();
console.log('S9 APK bridge ready; Bluetooth build verified');
