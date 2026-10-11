#pragma once
#include <cstdint>
#include <string>

namespace AecDiag {
// Native-only diagnostic processor. Audio callbacks must never block or allocate.
void start(const std::string& libraryPath, int sampleRate);
void stop();
void microphone(const int16_t* mono, int frames);
void render(const int16_t* interleaved, int frames, int channels);
void enableSpeex(bool enabled);
void setDelayMs(int delayMs);
std::string record(const std::string& pathPrefix, int seconds);
std::string status();
}
