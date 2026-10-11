const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/bridge/s9-apk-bridge.js','utf8');
const pure=source.slice(source.indexOf('function supportedFormat'),source.indexOf('const bt='));
const context=vm.createContext({});vm.runInContext(pure,context);
function command(){
 const data=new DataView(new ArrayBuffer(66));
 data.setUint32(31,32000,true);data.setUint32(35,32000,true);
 data.setUint8(39,4);data.setUint8(44,4);data.setUint16(49,16,true);data.setUint16(51,16,true);data.setUint8(53,2);data.setUint8(54,2);
 return data;
}
function pointer(data,at=0){return {add(n){return pointer(data,at+n)},readU8(){return data.getUint8(at)},readU16(){return data.getUint16(at,true)},readU32(){return data.getUint32(at,true)}};}
assert.equal(context.supportedFormat(pointer(command())),true);
for(const [offset,value,width] of [[31,16000,4],[35,16000,4],[39,5,1],[44,3,1],[49,8,2],[51,8,2],[53,1,1],[54,1,1],[55,1,1],[56,1,1]]){
 const data=command();data['setUint'+width*8](offset,value,true);
 assert.equal(context.supportedFormat(pointer(data)),false,'unsupported format accepted at '+offset);
}
console.log('PASS: 16 kHz signed PCM command accepted; rate, codec, sample width, sign and payload alignment mismatches rejected.');
