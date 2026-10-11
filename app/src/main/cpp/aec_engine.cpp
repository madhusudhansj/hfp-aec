#include "aec_engine.h"
#include <aaudio/AAudio.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <vector>

namespace {
constexpr int kRate = 16000;
constexpr int kFrame = 160;
std::mutex control;
std::atomic<bool> recording{false};
std::atomic<int> writers{0};
std::atomic<size_t> micUsed{0}, refUsed{0};
std::vector<int16_t> micData, refData;
std::atomic<int64_t> micStartNs{0}, refStartNs{0};
int micRate = 48000, renderRate = 48000;
std::string speexPath;
int delayMs = 100;
std::string lastStatus = "Ready";
int64_t monotonicNs() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec)*1000000000LL + t.tv_nsec;
}
void writeSamples(const int16_t* in, int frames, int channels,
                  std::vector<int16_t>& data,
                  std::atomic<size_t>& count, std::atomic<int64_t>& firstNs) {
    if (!recording.load(std::memory_order_acquire) || !in || frames <= 0) return;
    writers.fetch_add(1, std::memory_order_acq_rel);
    if (!recording.load(std::memory_order_acquire)) {
        writers.fetch_sub(1, std::memory_order_release); return;
    }
    if (!firstNs.load(std::memory_order_relaxed)) {
        const int64_t now = monotonicNs();
        int64_t expected = 0;
        firstNs.compare_exchange_strong(expected, now, std::memory_order_relaxed);
    }
    const size_t offset = count.load(std::memory_order_relaxed);
    const size_t n = std::min(static_cast<size_t>(frames),
                              offset < data.size() ? data.size()-offset : size_t(0));
    for (size_t i=0; i<n; ++i) data[offset+i] = in[i*channels];
    count.store(offset+n, std::memory_order_release);
    writers.fetch_sub(1, std::memory_order_release);
}
std::vector<int16_t> resample(const std::vector<int16_t>& samples,
                               size_t n, int inputRate) {
    if (inputRate <= 0 || n < 2) return {};
    const size_t outputN = static_cast<size_t>(std::floor(double(n)*kRate/inputRate));
    std::vector<int16_t> output(outputN);
    for (size_t i=0; i<outputN; ++i) {
        const double where = double(i)*inputRate/kRate;
        const size_t index = std::min(static_cast<size_t>(where), n-2);
        const double a = where - index;
        output[i] = int16_t(std::clamp(
            std::lround((1-a)*samples[index] + a*samples[index+1]),
            long(-32768), long(32767)));
    }
    return output;
}
bool wav(const std::string& path, const std::vector<int16_t>& pcm) {
    FILE* f = std::fopen(path.c_str(), "wb"); if (!f) return false;
    auto u16 = [&](uint16_t value) {
        unsigned char b[2]={static_cast<unsigned char>(value),
                            static_cast<unsigned char>(value>>8)};
        std::fwrite(b,1,2,f);
    };
    auto u32 = [&](uint32_t value) {
        unsigned char b[4]={static_cast<unsigned char>(value), static_cast<unsigned char>(value>>8),
                            static_cast<unsigned char>(value>>16),static_cast<unsigned char>(value>>24)};
        std::fwrite(b,1,4,f);
    };
    const uint32_t size = static_cast<uint32_t>(pcm.size()*sizeof(int16_t));
    std::fwrite("RIFF",1,4,f); u32(size+36);
    std::fwrite("WAVEfmt ",1,8,f); u32(16); u16(1); u16(1);
    u32(kRate); u32(kRate*2); u16(2); u16(16);
    std::fwrite("data",1,4,f); u32(size);
    const bool success = std::fwrite(pcm.data(),sizeof(int16_t),pcm.size(),f)==pcm.size();
    std::fclose(f); return success;
}
struct SpeexApi {
    void* lib=nullptr;
    void* (*init)(int,int)=nullptr;
    void (*destroy)(void*)=nullptr;
    void (*cancel)(void*,const int16_t*,const int16_t*,int16_t*)=nullptr;
    bool open(const std::string& path) {
        lib=dlopen(path.c_str(),RTLD_NOW|RTLD_LOCAL);
        if (!lib) return false;
        init=reinterpret_cast<decltype(init)>(dlsym(lib,"speex_echo_state_init"));
        destroy=reinterpret_cast<decltype(destroy)>(dlsym(lib,"speex_echo_state_destroy"));
        cancel=reinterpret_cast<decltype(cancel)>(dlsym(lib,"speex_echo_cancellation"));
        return init && destroy && cancel;
    }
    ~SpeexApi() { if(lib) dlclose(lib); }
};
}
namespace AecDiag {
void start(const std::string& path, int inputRate, int outputRate) {
    std::lock_guard<std::mutex> lk(control);
    recording.store(false);
    while(writers.load()) std::this_thread::yield();
    speexPath=path; micRate=inputRate; renderRate=outputRate;
    micData.clear(); refData.clear();
    micUsed=0; refUsed=0; micStartNs=0; refStartNs=0;
    lastStatus="Ready. Record 20 seconds to compare raw and Speex.";
}
void stop() {
    recording.store(false, std::memory_order_release);
    while(writers.load(std::memory_order_acquire)) std::this_thread::yield();
}
void microphone(const int16_t* mono, int frames) {
    writeSamples(mono, frames, 1, micData, micUsed, micStartNs);
}
void render(const int16_t* interleaved, int frames, int channels) {
    writeSamples(interleaved,frames,channels,refData,refUsed,refStartNs);
}
void setDelayMs(int delay) {
    std::lock_guard<std::mutex> lk(control);
    delayMs=std::clamp(delay,0,1500);
}
std::string begin(int seconds) {
    std::lock_guard<std::mutex> lk(control);
    if (recording.load()) return "Already recording";
    seconds=std::clamp(seconds,1,60);
    micData.assign(size_t(micRate)*(seconds+2),0);
    refData.assign(size_t(renderRate)*(seconds+2),0);
    micUsed=0; refUsed=0; micStartNs=0; refStartNs=0;
    recording.store(true, std::memory_order_release);
    return "Recording mic and exact output callback PCM";
}
std::string finish(const std::string& prefix) {
    stop();
    std::lock_guard<std::mutex> lk(control);
    const auto mic=resample(micData,micUsed.load(),micRate);
    const auto render=resample(refData,refUsed.load(),renderRate);
    if(mic.empty() || render.empty()) return "Capture incomplete; no usable mic/render samples";
    std::vector<int16_t> aligned(mic.size(),0), processed(mic.size(),0);
    const double differenceMs = double(micStartNs.load()-refStartNs.load())/1000000.;
    // Callback timestamps are approximate; delayMs is user-calibrated acoustic/output delay.
    const int shift = int(std::llround((differenceMs-delayMs)*kRate/1000.));
    for(size_t i=0;i<aligned.size();++i) {
        const int64_t src=int64_t(i)+shift;
        if(src>=0 && src<int64_t(render.size())) aligned[i]=render[size_t(src)];
    }
    if (!wav(prefix+"_raw.wav",mic) || !wav(prefix+"_reference.wav",aligned))
        return "Cannot write WAVs; check app files directory";
    SpeexApi speex;
    if(!speex.open(speexPath)) {
        return "Raw and reference exported; Speex library missing/unloadable: "+speexPath;
    }
    void* state=speex.init(kFrame, 1600); // 100 ms echo tail, 10 ms frames
    if(!state) return "Speex initialization failed";
    for(size_t i=0;i<mic.size();i+=kFrame) {
        int16_t in[kFrame]{}, ref[kFrame]{}, out[kFrame]{};
        const size_t n=std::min<size_t>(kFrame,mic.size()-i);
        std::memcpy(in,mic.data()+i,n*2);
        std::memcpy(ref,aligned.data()+i,n*2);
        speex.cancel(state,in,ref,out);
        std::memcpy(processed.data()+i,out,n*2);
    }
    speex.destroy(state);
    if(!wav(prefix+"_speex.wav",processed)) return "Speex processed but output WAV write failed";
    lastStatus="Exported raw, reference and Speex 16 kHz mono WAVs";
    return lastStatus+"; callback offset="+std::to_string(differenceMs)+" ms, configured delay="+std::to_string(delayMs)+" ms";
}
std::string status() {
    std::lock_guard<std::mutex> lk(control);
    return lastStatus + "\nAEC diagnostic recording: "+(recording.load()?"YES":"NO")
      + "\nCapture samples: "+std::to_string(micUsed.load())
      + " / reference samples: "+std::to_string(refUsed.load())
      + "\nAEC reference delay: "+std::to_string(delayMs)+" ms";
}
}
