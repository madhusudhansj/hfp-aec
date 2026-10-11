#pragma once
#include <cstdint>
#include <string>
namespace AecDiag {
void start(const std::string& libraryPath, int inputRate, int outputRate);
void stop();
void microphone(const int16_t* mono, int frames);
void render(const int16_t* interleaved, int frames, int channels);
void setDelayMs(int delayMs);
std::string begin(int seconds);
std::string finish(const std::string& pathPrefix);
std::string status();
}
