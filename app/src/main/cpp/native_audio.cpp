#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <time.h>
#include <string>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HfpEngineNative", __VA_ARGS__)

namespace {
constexpr double kPi = 3.14159265358979323846;
std::mutex gControl; // Held only on JNI control/status threads; never in audio callbacks.
AAudioStream *gInput = nullptr, *gOutput = nullptr;
std::atomic<bool> gTone{false};
std::atomic<int64_t> gSumSquares{0};
std::atomic<int32_t> gRmsCount{0}, gPeak{0};
std::atomic<int64_t> gCapturedFrames{0}, gRenderedFrames{0};
std::atomic<int> gLastError{0};
std::array<int16_t, 256> gWave{};
int32_t gOutChannels = 1, gOutputRate = 48000;
double gPhase = 0.0; // Output callback only while stream is started.

static aaudio_data_callback_result_t onInput(AAudioStream *, void *, void *buffer, int32_t frames) {
    auto *samples = static_cast<const int16_t*>(buffer);
    if (samples == nullptr || frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    int64_t squares = 0;
    int32_t peak = 0;
    for (int32_t i = 0; i < frames; ++i) {
        int32_t v = samples[i];
        int32_t absV = v < 0 ? -v : v;
        if (absV > peak) peak = absV;
        squares += static_cast<int64_t>(v) * v;
    }
    gSumSquares.store(squares, std::memory_order_relaxed);
    gRmsCount.store(frames, std::memory_order_release);
    gPeak.store(peak, std::memory_order_relaxed);
    gCapturedFrames.fetch_add(frames, std::memory_order_relaxed);
    // No recording is retained or sent anywhere in this v0.1 diagnostic.
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_data_callback_result_t onOutput(AAudioStream *, void *, void *buffer, int32_t frames) {
    auto *samples = static_cast<int16_t*>(buffer);
    if (samples == nullptr || frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    if (!gTone.load(std::memory_order_relaxed)) {
        std::memset(samples, 0, static_cast<size_t>(frames) * gOutChannels * sizeof(int16_t));
    } else {
        // A deliberately quiet ~660 Hz test tone, approximately -30 dBFS.
        const double step = 660.0 / static_cast<double>(gOutputRate);
        for (int32_t i = 0; i < frames; ++i) {
            const auto index = static_cast<int>(gPhase * 256.0) & 255;
            const int16_t v = gWave[index];
            for (int c = 0; c < gOutChannels; ++c) samples[i*gOutChannels+c] = v;
            gPhase += step;
            if (gPhase >= 1.0) gPhase -= 1.0;
        }
    }
    gRenderedFrames.fetch_add(frames, std::memory_order_relaxed);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void onError(AAudioStream *, void *, aaudio_result_t error) {
    // RT callback: record only. Java UI can show the failure.
    gLastError.store(error, std::memory_order_relaxed);
}

static void stopLocked() {
    gTone.store(false);
    if (gInput) {
        AAudioStream_requestStop(gInput);
        AAudioStream_close(gInput);
        gInput = nullptr;
    }
    if (gOutput) {
        AAudioStream_requestStop(gOutput);
        AAudioStream_close(gOutput);
        gOutput = nullptr;
    }
}

static aaudio_result_t openStream(aaudio_direction_t direction, int deviceId,
                                   int inputPreset, AAudioStream **result) {
    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) return r;
    AAudioStreamBuilder_setDirection(builder, direction);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    // Keep device-native sample rate for the initial low-latency diagnostic.
    // A later DSP stage can resample to WebRTC's chosen processing rate.
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);
    if (direction == AAUDIO_DIRECTION_INPUT) {
        AAudioStreamBuilder_setInputPreset(builder, inputPreset);
        AAudioStreamBuilder_setDataCallback(builder, onInput, nullptr);
    } else {
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setDataCallback(builder, onOutput, nullptr);
    }
    AAudioStreamBuilder_setErrorCallback(builder, onError, nullptr);
    r = AAudioStreamBuilder_openStream(builder, result);
    AAudioStreamBuilder_delete(builder);
    return r;
}

static std::string describeStream(AAudioStream *s) {
    if (!s) return "CLOSED";
    std::ostringstream o;
    o << "rate=" << AAudioStream_getSampleRate(s)
      << " channels=" << AAudioStream_getChannelCount(s)
      << " device=" << AAudioStream_getDeviceId(s)
      << " burst=" << AAudioStream_getFramesPerBurst(s)
      << " buffer=" << AAudioStream_getBufferSizeInFrames(s)
      << " perf=" << AAudioStream_getPerformanceMode(s)
      << " state=" << AAudioStream_getState(s);
    return o.str();
}

static jstring toJava(JNIEnv *env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_NativeAudio_start(JNIEnv *env, jclass, jint inputId, jint outputId, jint preset) {
    std::lock_guard<std::mutex> hold(gControl);
    stopLocked();
    for (int i=0; i<256; ++i) {
        gWave[i] = static_cast<int16_t>(std::round(1000.0 * std::sin(2.0*kPi*i/256.0)));
    }
    gSumSquares.store(0); gRmsCount.store(0); gPeak.store(0);
    gCapturedFrames.store(0); gRenderedFrames.store(0); gLastError.store(0);
    gPhase = 0;

    aaudio_result_t r = openStream(AAUDIO_DIRECTION_OUTPUT, outputId, preset, &gOutput);
    if (r != AAUDIO_OK) {
        std::string msg = std::string("Output OPEN FAILED: ") + AAudio_convertResultToText(r);
        LOGE("%s", msg.c_str());
        stopLocked();
        return toJava(env, msg);
    }
    gOutChannels = AAudioStream_getChannelCount(gOutput);
    gOutputRate = AAudioStream_getSampleRate(gOutput);
    r = openStream(AAUDIO_DIRECTION_INPUT, inputId, preset, &gInput);
    if (r != AAUDIO_OK) {
        std::string msg = std::string("Input OPEN FAILED: ") + AAudio_convertResultToText(r);
        LOGE("%s", msg.c_str());
        stopLocked();
        return toJava(env, msg);
    }
    // Start the output, then mic. No feedback loop: output is silence until test tone is enabled.
    r = AAudioStream_requestStart(gOutput);
    if (r == AAUDIO_OK) r = AAudioStream_requestStart(gInput);
    if (r != AAUDIO_OK) {
        std::string msg = std::string("START FAILED: ") + AAudio_convertResultToText(r);
        LOGE("%s", msg.c_str());
        stopLocked();
        return toJava(env, msg);
    }
    std::ostringstream result;
    result << "AAudio diagnostic RUNNING\nOUTPUT: " << describeStream(gOutput)
           << "\nINPUT:  " << describeStream(gInput)
           << "\nNo HCI / no WebRTC / no mic uplink in v0.1.";
    return toJava(env, result.str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_NativeAudio_stop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> hold(gControl);
    stopLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_hfpengine_s9_NativeAudio_setTestTone(JNIEnv *, jclass, jboolean enabled) {
    gTone.store(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hfpengine_s9_NativeAudio_stats(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> hold(gControl);
    if (!gInput || !gOutput) return toJava(env, "Native audio streams closed");
    int count = gRmsCount.load(std::memory_order_acquire);
    int64_t squares = gSumSquares.load(std::memory_order_relaxed);
    double rms = count > 0 ? std::sqrt(static_cast<double>(squares)/count)/32768.0 : 0.0;
    double dbfs = rms > 0.0000000001 ? 20.0*std::log10(rms) : -120.0;
    std::ostringstream o;
    o.setf(std::ios::fixed);
    o.precision(1);
    int64_t outPosition=0, outTime=0, inPosition=0, inTime=0;
    aaudio_result_t outTs = AAudioStream_getTimestamp(gOutput, CLOCK_MONOTONIC, &outPosition, &outTime);
    aaudio_result_t inTs = AAudioStream_getTimestamp(gInput, CLOCK_MONOTONIC, &inPosition, &inTime);
    o << "Native mic last-buffer RMS: " << dbfs << " dBFS"
      << "\nNative mic peak: " << gPeak.load()
      << " / 32768"
      << "\nCaptured frames: " << gCapturedFrames.load()
      << "\nRendered frames: " << gRenderedFrames.load()
      << "\nOutput timestamp: " << (outTs == AAUDIO_OK ? std::to_string(outPosition) + " frames @ " + std::to_string(outTime) + " ns" : AAudio_convertResultToText(outTs))
      << "\nInput timestamp: " << (inTs == AAUDIO_OK ? std::to_string(inPosition) + " frames @ " + std::to_string(inTime) + " ns" : AAudio_convertResultToText(inTs))
      << "\nAAudio error callback: " << gLastError.load()
      << "\nOUTPUT: " << describeStream(gOutput)
      << "\nINPUT: " << describeStream(gInput);
    return toJava(env, o.str());
}
