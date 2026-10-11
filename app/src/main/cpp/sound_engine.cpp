// S9 HFP Engine v0.2: local, full-duplex sound processor, no Bluetooth HCI.
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <api/audio/audio_processing.h>
#include <api/audio/builtin_audio_processing_builder.h>
#include <api/environment/environment_factory.h>
#include <atomic>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <time.h>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,"S9SoundEngine",__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,"S9SoundEngine",__VA_ARGS__)
namespace {
constexpr int RATE=48000, BLOCK=480, RING_SIZE=131072, MAX_RECORD_SECONDS=30;
using Clock=std::chrono::steady_clock;
template<size_t N> class Spsc {
    static_assert((N&(N-1))==0);
    std::array<int16_t,N> a{};
    std::atomic<uint32_t> w{0},r{0};
public:
    std::atomic<uint64_t> drops{0};
    void reset() {w=0; r=0; drops=0;}
    void push(const int16_t* p, int n) {
        uint32_t wi=w.load(std::memory_order_relaxed), ri=r.load(std::memory_order_acquire);
        unsigned capacity=N-(wi-ri);
        const int count=std::min(n,int(capacity));
        for(int i=0;i<count;i++) a[(wi+i)&(N-1)]=p[i];
        w.store(wi+count,std::memory_order_release);
        if(n>count) drops.fetch_add(n-count,std::memory_order_relaxed);
    }
    bool pop(int16_t* p,int n) {
        uint32_t ri=r.load(std::memory_order_relaxed), wi=w.load(std::memory_order_acquire);
        if((wi-ri)<unsigned(n)) return false;
        for(int i=0;i<n;i++) p[i]=a[(ri+i)&(N-1)];
        r.store(ri+n,std::memory_order_release);return true;
    }
    uint32_t depth() const {return w.load(std::memory_order_acquire)-r.load(std::memory_order_acquire);}
};
uint16_t u16(const uint8_t* p) {return uint16_t(p[0])|(uint16_t(p[1])<<8);}
uint32_t u32(const uint8_t* p){return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);}
bool get(FILE* f,void* p,size_t n){return std::fread(p,1,n,f)==n;}
bool loadWav(const std::string& path,std::vector<int16_t>& out,std::string& why){
    if(path.empty()) {why="No speech clip selected";return false;}
    FILE* f=std::fopen(path.c_str(),"rb");
    if(!f){why="Could not open speech WAV";return false;}
    uint8_t head[12];
    if(!get(f,head,12)||memcmp(head,"RIFF",4)||memcmp(head+8,"WAVE",4)){
        fclose(f);why="Speech input is not RIFF/WAVE";return false;
    }
    uint32_t rate=0,dataSize=0;uint16_t fmt=0,ch=0,bits=0,align=0;
    long dataAt=-1;
    for(int k=0;k<80;k++){
        uint8_t h[8]; if(!get(f,h,8))break;
        uint32_t size=u32(h+4);long start=ftell(f);
        if(size>16000000)break;
        if(memcmp(h,"fmt ",4)==0 && size>=16){
            uint8_t t[16];if(!get(f,t,16))break;
            fmt=u16(t);ch=u16(t+2);rate=u32(t+4);align=u16(t+12);bits=u16(t+14);
        }
        if(memcmp(h,"data",4)==0){dataAt=start;dataSize=size;}
        if(fseek(f,start+long(size)+(size&1),SEEK_SET))break;
    }
    if(fmt!=1||(ch!=1&&ch!=2)||bits!=16||align!=ch*2||
       rate<8000||rate>96000||dataSize>12000000||dataSize<align||dataAt<0){
        fclose(f);why="Use mono/stereo PCM16 WAV, <=12 MB, 8-96 kHz";return false;
    }
    if(fseek(f,dataAt,SEEK_SET)){fclose(f);why="Bad WAV offset";return false;}
    std::vector<uint8_t> data(dataSize);
    bool ok=get(f,data.data(),data.size());fclose(f);
    if(!ok){why="Truncated WAV";return false;}
    size_t n=data.size()/align;
    if(n<2||double(n)/rate>60){why="Speech fixture must be <=60 seconds";return false;}
    // Fixture-only linear resampling. Replace with bandlimited SRC for production.
    out.resize(size_t(std::floor(double(n)*RATE/rate)));
    for(size_t i=0;i<out.size();i++){
        double p=double(i)*rate/RATE;
        size_t j=std::min(size_t(p),n-2);
        double t=p-j;
        auto sample=[&](size_t index){
            size_t byte=index*align;
            auto a=int16_t(u16(data.data()+byte));
            auto b=ch==2?int16_t(u16(data.data()+byte+2)):a;
            return (double(a)+b)*0.5;
        };
        out[i]=int16_t(std::clamp(std::lround(sample(j)*(1-t)+sample(j+1)*t),long(-32768),long(32767)));
    }
    why="Loaded speech WAV";return !out.empty();
}
bool wav(const std::string& file,const std::vector<int16_t>& samples,size_t n){
    FILE* f=fopen(file.c_str(),"wb");if(!f)return false;
    auto le16=[&](uint16_t x){uint8_t b[]={uint8_t(x),uint8_t(x>>8)};fwrite(b,1,2,f);};
    auto le32=[&](uint32_t x){uint8_t b[]={uint8_t(x),uint8_t(x>>8),uint8_t(x>>16),uint8_t(x>>24)};fwrite(b,1,4,f);};
    uint32_t bytes=uint32_t(n*2);
    fwrite("RIFF",1,4,f);le32(36+bytes);fwrite("WAVEfmt ",1,8,f);
    le32(16);le16(1);le16(1);le32(RATE);le32(RATE*2);le16(2);le16(16);
    fwrite("data",1,4,f);le32(bytes);
    bool ok=fwrite(samples.data(),2,n,f)==n;fclose(f);return ok;
}
struct Speex {
    void* lib=nullptr;void* echo=nullptr;
    using Init=void* (*)(int,int);
    using Cancel=void (*)(void*,const int16_t*,const int16_t*,int16_t*);
    using Destroy=void (*)(void*);
    Cancel cancel=nullptr;Destroy destroy=nullptr;
    void close(){
        if(echo&&destroy)destroy(echo);
        echo=nullptr; if(lib)dlclose(lib);lib=nullptr;
    }
    bool open(const std::string& path){
        close();if(path.empty())return false;
        lib=dlopen(path.c_str(),RTLD_NOW|RTLD_LOCAL);
        if(!lib)return false;
        auto init=reinterpret_cast<Init>(dlsym(lib,"speex_echo_state_init"));
        cancel=reinterpret_cast<Cancel>(dlsym(lib,"speex_echo_cancellation"));
        destroy=reinterpret_cast<Destroy>(dlsym(lib,"speex_echo_state_destroy"));
        if(!init||!cancel||!destroy){close();return false;}
        echo=init(BLOCK,RATE/5); // 200 ms echo filter tail
        if(!echo){close();return false;}
        return true;
    }
    ~Speex(){close();}
};
// Streaming 3:1 FIR conversion. State persists across 7.5/10 ms boundaries.
struct ScoResampler {
    static constexpr int TAPS=63;
    std::array<double,TAPS> taps{},history{};
    int at=0,phase=0;
    ScoResampler(){
        double sum=0;
        for(int i=0;i<TAPS;i++){
            double x=i-(TAPS-1)/2,fc=7200.0/RATE;
            taps[i]=(x==0?2*fc:std::sin(2*3.141592653589793*fc*x)/(3.141592653589793*x))
                *(0.54-0.46*std::cos(2*3.141592653589793*i/(TAPS-1)));
            sum+=taps[i];
        }
        for(auto& t:taps)t/=sum;
    }
    int16_t tick(double value,double gain=1){
        history[at]=value;double result=0;int index=at;
        for(int k=0;k<TAPS;k++){result+=taps[k]*history[index];if(--index<0)index=TAPS-1;}
        if(++at==TAPS)at=0;
        return int16_t(std::clamp(std::lround(result*gain),long(-32768),long(32767)));
    }
};
class Engine{
public:
    AAudioStream *input=nullptr,*output=nullptr;
    Spsc<RING_SIZE> mic,reference;
    Spsc<8192> callDown,callUp;
    ScoResampler upsample,downsample;
    std::atomic<bool> bridge{false};
    std::atomic<uint64_t> playbackUnderruns{0},uplinkUnderruns{0},downTrimmed{0},upTrimmed{0};
    std::atomic<bool> running{false},playing{false},tone{false};
    std::atomic<int> mode{1},noise{1},agc{0},delay{-1},configVersion{1},lastApmError{0};
    std::atomic<int> measuredDelay{-1},appliedDelay{80},aecDelay{-1};
    std::atomic<int> timestampIn{AAUDIO_ERROR_INVALID_STATE},timestampOut{AAUDIO_ERROR_INVALID_STATE};
    std::atomic<int> timingReason{1}; // 0 valid, 1 warmup, 2 input error, 3 output error, 4 range, 5 dropped samples
    std::atomic<float> rawTimingMs{NAN};
    std::atomic<int> aecMedian{-1},aecDeviation{-1};
    std::atomic<float> erl{NAN},erle{NAN},residualEcho{NAN},divergence{NAN};
    std::atomic<int64_t> aecUpdatedMs{0};
    std::atomic<float> rawDb{-120},cleanDb{-120};
    std::atomic<int> rawPeak{0},cleanPeak{0};
    std::atomic<int> maxRawPeak{0},maxCleanPeak{0};
    std::atomic<bool> resetPeaks{false};
    std::atomic<uint64_t> cleanClipped{0},referenceMissing{0};
    std::atomic<int> error{0}, actualInChannels{0}, actualInRate{0},actualOutRate{0};
    std::atomic<uint64_t> captured{0},rendered{0},processed{0},processingNs{0},processingNsMax{0},stereoDifference{0},stereoEnergy{0};
    std::atomic<bool> speexAvailable{false};
    std::atomic<uint32_t> clipPos{0};
    std::atomic<int> recordState{0}; // 0 idle, 1 recording, 2 stop request, 3 complete
    std::vector<int16_t> recRaw,recApm,recRef;
    std::vector<int16_t> speech;
    std::string speechNote,speexPath;
    std::thread worker;
    int inputId=0,outputId=0,capturePreset=5,requestedChannels=1;
    int reportedInId=0,reportedOutId=0,actualPreset=0;
    double phase=0;
    uint32_t sampleIndex=0; // only touched in output callback
    static aaudio_data_callback_result_t inCb(AAudioStream*,void* context,void* data,int32_t frames){
        auto& e=*static_cast<Engine*>(context);
        auto* s=static_cast<int16_t*>(data);
        int channels=e.actualInChannels.load(std::memory_order_relaxed);
        if(!s || (channels!=1 && channels!=2))return AAUDIO_CALLBACK_RESULT_CONTINUE;
        // Build mono mic input. No allocation, blocking or DSP in callback.
        int16_t b[512];int offset=0;
        while(offset<frames){
            int n=std::min(512,int(frames)-offset);
            for(int j=0;j<n;j++){
                const int16_t l=s[(offset+j)*channels];
                const int16_t r=channels==2?s[(offset+j)*channels+1]:l;
                b[j]=int16_t((int(l)+int(r))/2);
                if(channels==2){
                    e.stereoDifference.fetch_add(uint64_t(std::abs(int(l)-int(r))),std::memory_order_relaxed);
                    e.stereoEnergy.fetch_add(uint64_t(std::abs(int(l))+std::abs(int(r))),std::memory_order_relaxed);
                }
            }
            e.mic.push(b,n);offset+=n;
        }
        e.captured.fetch_add(frames,std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    static aaudio_data_callback_result_t outCb(AAudioStream*,void* context,void* data,int32_t frames){
        auto& e=*static_cast<Engine*>(context);
        auto* out=static_cast<int16_t*>(data);
        if(!out)return AAUDIO_CALLBACK_RESULT_CONTINUE;
        bool doSpeech=e.playing.load(std::memory_order_relaxed)&&!e.speech.empty();
        bool doTone=e.tone.load(std::memory_order_relaxed);
        bool call=e.bridge.load(std::memory_order_acquire);
        // Trim only on the consumer side; never overwrite unread SPSC data.
        if(call && e.callDown.depth()>1440){
            int16_t discard[360];
            while(e.callDown.depth()>1080 && e.callDown.pop(discard,360))e.downTrimmed.fetch_add(360);
        }
        for(int i=0;i<frames;i++){
            int32_t value=0;
            if(call){
                int16_t sample=0;
                if(!e.callDown.pop(&sample,1))e.playbackUnderruns.fetch_add(1,std::memory_order_relaxed);
                value=sample;
            }else if(doSpeech){
                value=e.speech[e.sampleIndex];
                if(++e.sampleIndex>=e.speech.size())e.sampleIndex=0;
            }else if(doTone){
                value=int32_t(1000*std::sin(e.phase));
                e.phase+=2.0*3.14159265358979323846*660/RATE;
                if(e.phase>=6.283185307179586)e.phase-=6.283185307179586;
            }
            out[i]=int16_t(std::clamp(value,-32768,32767));
        }
        e.reference.push(out,frames);
        e.rendered.fetch_add(frames,std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    static void errCb(AAudioStream*,void* context,aaudio_result_t code){
        static_cast<Engine*>(context)->error.store(code,std::memory_order_relaxed);
    }
    bool makeStream(bool capture,int device,int preset,int channels,AAudioStream** stream,std::string& reason){
        AAudioStreamBuilder* b=nullptr;
        auto rc=AAudio_createStreamBuilder(&b);
        if(rc!=AAUDIO_OK){reason="Builder error";return false;}
        AAudioStreamBuilder_setDirection(b,capture?AAUDIO_DIRECTION_INPUT:AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSampleRate(b,RATE);
        AAudioStreamBuilder_setChannelCount(b,channels);
        AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(b,AAUDIO_SHARING_MODE_SHARED);
        if(device>0)AAudioStreamBuilder_setDeviceId(b,device);
        if(capture){
            AAudioStreamBuilder_setInputPreset(b,static_cast<aaudio_input_preset_t>(preset));
            AAudioStreamBuilder_setDataCallback(b,inCb,this);
        }else{
            AAudioStreamBuilder_setUsage(b,AAUDIO_USAGE_MEDIA);
            AAudioStreamBuilder_setDataCallback(b,outCb,this);
        }
        AAudioStreamBuilder_setErrorCallback(b,errCb,this);
        rc=AAudioStreamBuilder_openStream(b,stream);
        AAudioStreamBuilder_delete(b);
        if(rc!=AAUDIO_OK){reason=AAudio_convertResultToText(rc);return false;}
        return true;
    }
    bool start(const std::string& fixture,const std::string& speex,int inDevice,int outDevice,int preset,int micChannels,std::string& reason){
        inputId=inDevice;outputId=outDevice;capturePreset=preset;requestedChannels=micChannels;
        speexPath=speex; speechNote.clear();
        if(!fixture.empty())loadWav(fixture,speech,speechNote);
        if(!makeStream(false,outputId,preset,1,&output,reason))return false;
        if(!makeStream(true,inputId,preset,micChannels,&input,reason)){stop();return false;}
        actualInRate=AAudioStream_getSampleRate(input);
        actualOutRate=AAudioStream_getSampleRate(output);
        actualInChannels=AAudioStream_getChannelCount(input);
        actualPreset=AAudioStream_getInputPreset(input);
        reportedInId=AAudioStream_getDeviceId(input);reportedOutId=AAudioStream_getDeviceId(output);
        if(actualInRate!=RATE||actualOutRate!=RATE||
           (actualInChannels!=1&&actualInChannels!=2)||AAudioStream_getChannelCount(output)!=1){
            reason="Expected actual PCM16 48kHz input/output and mono output; inspect audio route";
            stop();return false;
        }
        int burst=AAudioStream_getFramesPerBurst(output);
        if(burst>0)AAudioStream_setBufferSizeInFrames(output,2*burst);
        running=true;
        worker=std::thread([this](){process();});
        auto rc=AAudioStream_requestStart(output);
        if(rc==AAUDIO_OK)rc=AAudioStream_requestStart(input);
        if(rc!=AAUDIO_OK){reason=AAudio_convertResultToText(rc);stop();return false;}
        reason="Real-time engine armed; "+speechNote;return true;
    }
    void stop(){
        playing=false;tone=false;running=false;
        if(input)AAudioStream_requestStop(input);
        if(output)AAudioStream_requestStop(output);
        if(worker.joinable())worker.join();
        if(input){AAudioStream_close(input);input=nullptr;}
        if(output){AAudioStream_close(output);output=nullptr;}
        recordState=0;
    }
    ~Engine(){stop();}
    static float level(const int16_t* samples, int& peak, uint64_t& clipped) {
        double squares=0; peak=0; clipped=0;
        for(int i=0;i<BLOCK;++i){
            int value=std::abs(int(samples[i]));
            peak=std::max(peak,value); squares+=double(samples[i])*samples[i];
            if(samples[i]==INT16_MIN || samples[i]==INT16_MAX)++clipped;
        }
        return float(10*std::log10(std::max(squares/BLOCK/(32768.0*32768.0),1e-12)));
    }
    void updatePeakHolds(int raw,int clean) {
        if(resetPeaks.exchange(false)){maxRawPeak=0;maxCleanPeak=0;}
        maxRawPeak=std::max(maxRawPeak.load(),raw);
        maxCleanPeak=std::max(maxCleanPeak.load(),clean);
    }
    static int recordingPeak(const std::vector<int16_t>& samples,size_t count) {
        int peak=0;
        for(size_t i=0;i<count;i++)peak=std::max(peak,std::abs(int(samples[i])));
        return peak;
    }
    // APM delay = (render time - reverse call) + (capture call - capture time).
    // The two calls are adjacent, so use the hardware times of their first samples.
    int timestampDelay(uint64_t micFrame, uint64_t renderFrame) {
        int64_t ip=0,it=0,op=0,ot=0;
        auto inResult=AAudioStream_getTimestamp(input,CLOCK_MONOTONIC,&ip,&it);
        auto outResult=AAudioStream_getTimestamp(output,CLOCK_MONOTONIC,&op,&ot);
        timestampIn=inResult; timestampOut=outResult;
        if(inResult!=AAUDIO_OK){timingReason=2;rawTimingMs=NAN;return -1;}
        if(outResult!=AAUDIO_OK){timingReason=3;rawTimingMs=NAN;return -1;}
        double captureTime=double(it)+(double(micFrame)-ip)*1e9/RATE;
        double renderTime=double(ot)+(double(renderFrame)-op)*1e9/RATE;
        double ms=(renderTime-captureTime)/1e6;
        rawTimingMs=float(ms);
        if(!std::isfinite(ms)||ms < -10 || ms > 500){timingReason=4;return -1;}
        timingReason=0;
        return std::clamp(int(std::lround(ms)),0,500);
    }
    void process(){
        webrtc::scoped_refptr<webrtc::AudioProcessing> apm;
        const webrtc::StreamConfig format(RATE,1);
        Speex speex;
        int lastVersion=-1;
        uint64_t micFrame=0,renderFrame=0,blocks=0;
        int16_t raw[BLOCK]{},far[BLOCK]{},farForApm[BLOCK]{},clean[BLOCK]{};
        while(running.load(std::memory_order_acquire)){
            if(recordState.load(std::memory_order_acquire)==2)recordState.store(3,std::memory_order_release);
            // Do not inject fake silence when independently scheduled callbacks arrive apart.
            // There is one consumer per ring, so once both depths suffice these pops succeed.
            if(mic.depth()<BLOCK || reference.depth()<BLOCK){
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            if(!mic.pop(raw,BLOCK))continue;
            bool haveReference=reference.pop(far,BLOCK);
            if(!haveReference){
                std::memset(far,0,sizeof(far));
                referenceMissing.fetch_add(1);
            }
            if(blocks%10==0){
                if(mic.drops==0 && reference.drops==0)
                    measuredDelay=timestampDelay(micFrame,renderFrame);
                else { measuredDelay=-1; timingReason=5; }
            }
            micFrame+=BLOCK;
            if(haveReference)renderFrame+=BLOCK;
            ++blocks;
            auto start=Clock::now();
            const int version=configVersion.load(std::memory_order_relaxed);
            if(version!=lastVersion){
                lastVersion=version;
                aecDelay=-1; aecMedian=-1; aecDeviation=-1;
                erl=NAN; erle=NAN; residualEcho=NAN; divergence=NAN; aecUpdatedMs=0;
                webrtc::AudioProcessing::Config cfg;
                cfg.echo_canceller.enabled=true;
                cfg.high_pass_filter.enabled=true;
                cfg.noise_suppression.enabled=noise.load()>0;
                cfg.noise_suppression.level= noise.load()>1 ?
                    webrtc::AudioProcessing::Config::NoiseSuppression::kHigh :
                    webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
                cfg.gain_controller2.enabled=agc.load()!=0;
                cfg.gain_controller2.adaptive_digital.enabled=agc.load()!=0;
                cfg.gain_controller2.adaptive_digital.max_gain_db=30.0f;
                cfg.gain_controller2.adaptive_digital.initial_gain_db=6.0f;
                cfg.gain_controller2.adaptive_digital.headroom_db=6.0f;
                // AEC3 estimates acoustic alignment internally. Do not force external-delay mode
                // or feed its internal delay back into the distinct hardware stream-delay hint.
                webrtc::EchoCanceller3Config echoConfig;
                echoConfig.delay.use_external_delay_estimator=false;
                webrtc::BuiltinAudioProcessingBuilder builder(cfg);
                builder.SetEchoCancellerConfig(echoConfig,std::nullopt);
                apm=builder.Build(webrtc::CreateEnvironment());
                if(!apm)LOGE("APM builder failed");
                if(mode.load()==2) {
                    bool loaded=speex.open(speexPath);
                    speexAvailable=loaded;
                    if(!loaded)LOGE("Speex not available: %s",speexPath.c_str());
                } else speexAvailable=false;
            }
            std::memcpy(clean,raw,sizeof(raw));
            int active=mode.load();
            if(active==1 && apm){
                std::memcpy(farForApm,far,sizeof(far));
                int e1=apm->ProcessReverseStream(farForApm,format,format,farForApm);
                int requested=delay.load(),estimate=measuredDelay.load();
                int used=requested>=0?requested:(estimate>=0?estimate:80);
                appliedDelay=used;
                int delayError=apm->set_stream_delay_ms(used);
                int e2=apm->ProcessStream(raw,format,format,clean);
                if(e1||e2||delayError)lastApmError.store(e1?e1:(e2?e2:delayError));
                if(blocks%100==0){
                    auto diagnostics=apm->GetStatistics();
                    aecDelay=diagnostics.delay_ms.value_or(-1);
                    aecMedian=diagnostics.delay_median_ms.value_or(-1);
                    aecDeviation=diagnostics.delay_standard_deviation_ms.value_or(-1);
                    erl=float(diagnostics.echo_return_loss.value_or(NAN));
                    erle=float(diagnostics.echo_return_loss_enhancement.value_or(NAN));
                    residualEcho=float(diagnostics.residual_echo_likelihood.value_or(NAN));
                    divergence=float(diagnostics.divergent_filter_fraction.value_or(NAN));
                    aecUpdatedMs=std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
                }
            }else if(active==2 && speex.echo){
                speex.cancel(speex.echo,raw,far,clean);
            }
            int rp=0,cp=0;uint64_t rc=0,cc=0;
            rawDb=level(raw,rp,rc);cleanDb=level(clean,cp,cc);
            rawPeak=rp;cleanPeak=cp;cleanClipped.fetch_add(cc);
            updatePeakHolds(rp,cp);
            if(bridge.load(std::memory_order_acquire)){
                int16_t packet[BLOCK/3];int count=0;
                for(int i=0;i<BLOCK;i++){
                    int16_t filtered=downsample.tick(clean[i]);
                    if(++downsample.phase==3){downsample.phase=0;packet[count++]=filtered;}
                }
                callUp.push(packet,count);
            }
            // The worker's clean[] block is the future HFP uplink and is currently exported for listening.
            if(recordState.load(std::memory_order_acquire)==1){
                const uint32_t offset=clipPos.load(std::memory_order_relaxed);
                if(offset+BLOCK<=recRaw.size()){
                    std::memcpy(recRaw.data()+offset,raw,sizeof(raw));
                    std::memcpy(recApm.data()+offset,clean,sizeof(clean));
                    std::memcpy(recRef.data()+offset,far,sizeof(far));
                    clipPos.store(offset+BLOCK,std::memory_order_release);
                }else recordState.store(3,std::memory_order_release);
            }
            auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now()-start).count();
            processingNs.fetch_add(ns); 
            auto maxNs=processingNsMax.load();
            while(ns>int64_t(maxNs)&&!processingNsMax.compare_exchange_weak(maxNs,ns)){}
            processed.fetch_add(BLOCK,std::memory_order_relaxed);
        }
        if(recordState.load()==2)recordState=3;
    }
    bool beginRecord(){
        if(recordState==1||recordState==2)return false;
        recRaw.assign(RATE*MAX_RECORD_SECONDS,0);
        recApm.assign(RATE*MAX_RECORD_SECONDS,0);
        recRef.assign(RATE*MAX_RECORD_SECONDS,0);
        clipPos=0;
        recordState.store(1,std::memory_order_release);
        return true;
    }
    std::string finish(const std::string& prefix){
        if(recordState==1)recordState=2;
        for(int i=0;i<250 && recordState!=3;i++)std::this_thread::sleep_for(std::chrono::milliseconds(10));
        if(recordState!=3)return "Recording worker did not acknowledge stop; not exporting";
        const size_t n=clipPos.load();
        if(n<480){recordState=0;return "No microphone audio captured";}
        bool ok=wav(prefix+"_mic_raw.wav",recRaw,n) &&
                wav(prefix+"_mic_processed.wav",recApm,n) &&
                wav(prefix+"_farend_reference.wav",recRef,n);
        FILE* meta=fopen((prefix+"_metadata.json").c_str(),"wb");
        if(meta){
            const char* engine=mode==1?"webrtc_apm":(mode==2?(speexAvailable?"speex":"speex_unavailable_bypass"):"bypass");
            fprintf(meta,"{\n \"sample_rate\":48000,\"channels\":1,\"frames\":%zu,"
               "\n \"engine\":\"%s\",\"delay_ms\":%d,\"noise_mode\":%d,\"agc\":%d,"
               "\n \"input_device_id\":%d,\"output_device_id\":%d,"
               "\n \"actual_input_channels\":%d,"
               "\n \"mic_dropped\":%llu,\"reference_dropped\":%llu,"
               "\n \"apm_error\":%d,\"delay_setting_ms\":%d,\"timestamp_delay_ms\":%d,"
               "\n \"agc_adaptive\":%s,\"reference_missing_blocks\":%llu,"
               "\n \"requested_input_preset\":%d,\"actual_input_preset\":%d,"
               "\n \"recording_raw_max_peak\":%d,\"recording_processed_max_peak\":%d\n}\n",
               n,engine,appliedDelay.load(),noise.load(),agc.load(),reportedInId,reportedOutId,
               actualInChannels.load(),(unsigned long long)mic.drops.load(),
               (unsigned long long)reference.drops.load(),lastApmError.load(),delay.load(),measuredDelay.load(),
               agc.load()?"true":"false",(unsigned long long)referenceMissing.load(),capturePreset,actualPreset,
               recordingPeak(recRaw,n),recordingPeak(recApm,n));
            if(ferror(meta))ok=false;
            if(fclose(meta)!=0)ok=false;
        }else ok=false;
        recordState=ok?0:3;
        return ok?"Saved 3 synchronized PCM WAVs + metadata at "+prefix:
                  "WAV export failed; check storage access";
    }
    static std::string metric(float value,const char* unit="") {
        if(!std::isfinite(value))return "not available";
        std::ostringstream out;out.setf(std::ios::fixed);out.precision(1);
        out<<value<<unit;return out.str();
    }
    static std::string milliseconds(int value) {
        return value<0?"not available":std::to_string(value)+" ms";
    }
    std::string timingStatus() const {
        switch(timingReason.load()){
            case 0:return "hardware timestamps available";
            case 2:return std::string("input timestamp: ")+AAudio_convertResultToText(timestampIn.load());
            case 3:return std::string("output timestamp: ")+AAudio_convertResultToText(timestampOut.load());
            case 4:return "timestamp difference out of range: "+metric(rawTimingMs.load()," ms");
            case 5:return "sample drops; restart to restore frame positions";
            default:return "waiting for paired microphone / reference audio";
        }
    }
    std::string monitor() const {
        if(mode.load()!=1)return "AEC3 inactive — select WebRTC APM to view echo metrics.";
        std::ostringstream o;
        int64_t updated=aecUpdatedMs.load();
        int64_t now=std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
        o<<"AEC3 LIVE MONITOR"
         <<"\nMetrics: "<<(updated?"last sampled "+std::to_string(std::max(int64_t(0),now-updated))+" ms ago":"waiting for processed audio")
         <<"\nExternal stream-delay hint sent to APM: "<<appliedDelay.load()<<" ms"
         <<(delay>=0?" (manual)":(measuredDelay>=0?" (hardware estimate)":" (unmeasured fallback)"))
         <<"\nHardware stream estimate: "<<milliseconds(measuredDelay.load())
         <<"\nHardware timing status: "<<timingStatus()
         <<"\nInput / output timestamp status: "<<AAudio_convertResultToText(timestampIn.load())<<" / "<<AAudio_convertResultToText(timestampOut.load())
         <<"\nAEC3 alignment: internal automatic delay tracking"
         <<"\nAEC3 internal delay estimate: "<<milliseconds(aecDelay.load())
         <<"\nDelay median / standard deviation: "<<milliseconds(aecMedian.load())<<" / "<<milliseconds(aecDeviation.load())
         <<"\nEcho reduction (ERLE): "<<metric(erle.load()," dB")
         <<"\nEcho return loss (ERL): "<<metric(erl.load()," dB")
         <<"\nResidual echo likelihood: "<<(std::isfinite(residualEcho.load())?metric(residualEcho.load()*100,"%"):
                 "not enabled — separate echo detector not attached")
         <<"\nDivergent filter fraction: "<<(std::isfinite(divergence.load())?metric(divergence.load()*100,"%"):
                 "not reported by this AEC3 stats path")
         <<"\nTotal DSP level change: "<<metric(cleanDb.load()-rawDb.load()," dB (includes NS / AGC)")
         <<"\nReference gaps: "<<referenceMissing.load()<<"; dropped samples mic / render: "<<mic.drops.load()<<" / "<<reference.drops.load()
         <<"\nAPM error: "<<lastApmError.load()<<"; audio stream error: "<<error.load()
         <<"\nThe external hint is not a fixed AEC3 alignment. Play far-end speech for internal echo estimates.";
        return o.str();
    }
    std::string stats() const {
        std::ostringstream o;
        const auto p=processed.load();
        double ms=p?double(processingNs.load())/(p/BLOCK)/1e6:0.;
        o<<"APM WORKER: "<<(running?"RUNNING":"STOPPED")
          <<"\nENGINE: "<<(mode==1?"WebRTC AEC3":(mode==2?"Speex":"Bypass"))
          <<"\nRaw mic RMS "<<rawDb.load()<<" dBFS; peak "<<rawPeak.load()<<" / 32768"
          <<"\nProcessed RMS "<<cleanDb.load()<<" dBFS; peak "<<cleanPeak.load()<<" / 32768"
          <<"\nRaw MAX since arm/reset: "<<maxRawPeak.load()<<" / 32768"
          <<"\nProcessed MAX since arm/reset: "<<maxCleanPeak.load()<<" / 32768"
          <<"\nCapture preset requested / actual: "<<capturePreset<<" / "<<actualPreset
          <<"\nProcessed clipped samples "<<cleanClipped.load()
          <<"\nAdaptive speech gain "<<(agc?"ON (APM mode only)":"OFF")
          <<"\nStream delay: "<<(delay>=0?std::to_string(delay.load())+" ms manual":
                  (measuredDelay>=0?std::to_string(measuredDelay.load())+" ms timestamp estimate":"80 ms FALLBACK; timing unavailable / reference gaps"))
          <<"\nAEC internal delay: "<<(aecDelay>=0?std::to_string(aecDelay.load())+" ms":"not available")
          <<"\nReference missing blocks "<<referenceMissing.load()
          <<"\nCapture device "<<reportedInId<<", channels "<<actualInChannels
          <<", rate "<<actualInRate<<"\nOutput device "<<reportedOutId<<", rate "<<actualOutRate
          <<"\nMic frames "<<captured<<"; played "<<rendered<<"; processed "<<p
          <<"\nReference queue "<<reference.depth()<<" frames; mic queue "<<mic.depth()
          <<"\nDropped mic "<<mic.drops<<", ref "<<reference.drops
          <<"\nDSP average "<<ms<<" ms / 10ms; max "<<processingNsMax.load()/1e6<<" ms"
          <<"\nAPM error "<<lastApmError<<"; AAudio error "<<error
          <<"\nStereo difference ratio "<<(stereoEnergy?double(stereoDifference.load())/stereoEnergy.load():0)
          <<"\nSpeex loaded "<<speexAvailable
          <<"\nRecording state "<<recordState<<", samples "<<clipPos
          <<"\nSpeech: "<<speechNote;
        return o.str();
    }
    std::string compact() const {
        std::ostringstream o;
        o<<"Mode: "<<(bridge?"Bluetooth call":"Sound Engine Test")
         <<"\nMic RMS / peak: "<<metric(rawDb.load()," dBFS")<<" / "<<rawPeak.load()
         <<"; max "<<maxRawPeak.load()
         <<"\nProcessed RMS / peak: "<<metric(cleanDb.load()," dBFS")<<" / "<<cleanPeak.load()
         <<"; max "<<maxCleanPeak.load()<<"; clipped "<<cleanClipped.load()
         <<"\nAEC3 delay: "<<milliseconds(aecDelay.load())<<"; ERLE: "<<metric(erle.load()," dB")
         <<"\nStream hint: "<<appliedDelay.load()<<" ms; "<<timingStatus()
         <<"\nCall queues down / up: "<<callDown.depth()/48.0<<" / "<<callUp.depth()/16.0<<" ms"
         <<"\nCall missing samples down / up: "<<playbackUnderruns.load()<<" / "<<uplinkUnderruns.load()
         <<"; trimmed "<<downTrimmed.load()<<" / "<<upTrimmed.load()
         <<"\nDSP max "<<processingNsMax.load()/1e6<<" ms; APM / audio errors "<<lastApmError.load()<<" / "<<error.load();
        if(output)o<<"\nOutput buffer: "<<AAudioStream_getBufferSizeInFrames(output)*1000.0/RATE<<" ms";
        return o.str();
    }
};
std::mutex engineMutex;
std::unique_ptr<Engine> engine;
jstring str(JNIEnv* env,const std::string& v){return env->NewStringUTF(v.c_str());}
std::string jstr(JNIEnv* env,jstring s){
    if(!s)return {};
    const char* p=env->GetStringUTFChars(s,nullptr);
    std::string result(p?p:"");
    if(p)env->ReleaseStringUTFChars(s,p);
    return result;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_EngineNative_start(JNIEnv* env,jclass,jstring wavPath,jstring speexPath,
                                         jint micDevice,jint speakerDevice,jint preset,jint channels){
    std::lock_guard<std::mutex> lock(engineMutex);
    if(engine)engine->stop();
    engine=std::make_unique<Engine>();
    std::string reason;
    if(!engine->start(jstr(env,wavPath),jstr(env,speexPath),micDevice,speakerDevice,preset,channels,reason)){
        engine.reset();
        return str(env,"ERROR: "+reason);
    }
    return str(env,reason);
}
extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_EngineNative_stop(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    engine.reset();
}
extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_EngineNative_bridgeEnable(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    if(engine)engine->bridge.store(true,std::memory_order_release);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hfpengine_s9_EngineNative_bridgeExchange(JNIEnv* env,jclass,jbyteArray bytes){
    if(!bytes || env->GetArrayLength(bytes)!=240)return nullptr;
    uint8_t packet[240];env->GetByteArrayRegion(bytes,0,240,reinterpret_cast<jbyte*>(packet));
    int16_t incoming[360],outgoing[120]{};
    {
        std::lock_guard<std::mutex> lock(engineMutex);
        if(engine && engine->bridge && engine->running){
            for(int i=0;i<120;i++){
                int16_t s=int16_t(u16(packet+i*2));
                incoming[3*i]=engine->upsample.tick(s,3);
                incoming[3*i+1]=engine->upsample.tick(0,3);
                incoming[3*i+2]=engine->upsample.tick(0,3);
            }
            engine->callDown.push(incoming,360);
            int16_t discard[120];
            while(engine->callUp.depth()>480 && engine->callUp.pop(discard,120))engine->upTrimmed.fetch_add(120);
            if(!engine->callUp.pop(outgoing,120))engine->uplinkUnderruns.fetch_add(120);
        }
    }
    for(int i=0;i<120;i++){uint16_t v=uint16_t(outgoing[i]);packet[2*i]=uint8_t(v);packet[2*i+1]=uint8_t(v>>8);}
    jbyteArray result=env->NewByteArray(240);
    if(result)env->SetByteArrayRegion(result,0,240,reinterpret_cast<jbyte*>(packet));
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_EngineNative_compactStats(JNIEnv* env,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    return str(env,engine?engine->compact():"Microphone / speaker inactive");
}
extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_EngineNative_setPlayback(JNIEnv*,jclass,jint value){
    std::lock_guard<std::mutex> lock(engineMutex);
    if(engine){engine->playing=(value==1);engine->tone=(value==2);}
}
extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_EngineNative_config(JNIEnv*,jclass,jint mode,jint ns,jboolean agc,jint delay){
    std::lock_guard<std::mutex> lock(engineMutex);
    if(engine){
        engine->mode=std::clamp(int(mode),0,2);
        engine->noise=std::clamp(int(ns),0,2);
        engine->agc=agc==JNI_TRUE;
        engine->delay=std::clamp(int(delay),-1,500);
        engine->configVersion.fetch_add(1);
    }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hfpengine_s9_EngineNative_record(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    return engine && engine->beginRecord()?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_EngineNative_export(JNIEnv* env,jclass,jstring prefix){
    std::lock_guard<std::mutex> lock(engineMutex);
    return str(env,engine?engine->finish(jstr(env,prefix)):"Engine stopped");
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_EngineNative_stats(JNIEnv* env,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    return str(env,engine?engine->stats():"Sound engine stopped");
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_EngineNative_aecMonitor(JNIEnv* env,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    return str(env,engine?engine->monitor():"AEC3 monitor inactive — arm the sound engine.");
}
extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_EngineNative_resetPeakMeters(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(engineMutex);
    if(engine)engine->resetPeaks=true;
}
// Deterministic meter verification: no hardware stream or microphone access.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hfpengine_s9_EngineNative_verifyPeakMeters(JNIEnv*,jclass){
    int16_t samples[BLOCK]{};
    samples[0]=INT16_MIN;samples[1]=INT16_MAX;samples[2]=-32767;
    int peak=0;uint64_t clipped=0;
    float rms=Engine::level(samples,peak,clipped);
    double expected=10*std::log10((32768.0*32768+2*32767.0*32767)/BLOCK/(32768.0*32768));
    if(peak!=32768 || clipped!=2 || std::abs(rms-expected)>0.001)return JNI_FALSE;
    std::vector<int16_t> recording{123,-200,32767,INT16_MIN};
    if(Engine::recordingPeak(recording,recording.size())!=32768)return JNI_FALSE;
    auto test=std::make_unique<Engine>();
    test->updatePeakHolds(300,400);test->updatePeakHolds(100,200);
    if(test->maxRawPeak!=300 || test->maxCleanPeak!=400)return JNI_FALSE;
    test->resetPeaks=true;test->updatePeakHolds(50,60);
    return test->maxRawPeak==50 && test->maxCleanPeak==60?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hfpengine_s9_EngineNative_verifyScoResampler(JNIEnv*,jclass){
    auto energy=[](double hz){
        ScoResampler filter;double sum=0;int count=0;
        for(int i=0;i<4800;i++){
            int16_t s=filter.tick(10000*std::sin(2*3.141592653589793*hz*i/RATE));
            if(i>300 && i%3==2){sum+=double(s)*s;count++;}
        }
        return sum/count;
    };
    double speech=energy(1000),alias=energy(12000);
    if(speech<45000000 || alias/speech>0.0001)return JNI_FALSE; // >40 dB alias rejection
    ScoResampler interpolate;double sum=0;int count=0;
    for(int packet=0;packet<8;packet++)for(int sample=0;sample<120;sample++)for(int phase=0;phase<3;phase++){
        int16_t s=interpolate.tick(phase==0?1000:0,3);
        if(packet>0){sum+=s;count++;}
    }
    return std::abs(sum/count-1000)<2?JNI_TRUE:JNI_FALSE;
}
