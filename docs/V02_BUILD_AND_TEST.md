# v0.2 Sound Engine — WebRTC AEC3 Test Build

**Status:** Source implementation for the S9+ Android 12L, arm64. No Bluetooth HCI bridge, no ROM patches, no automatic microphone start at reboot.

The original v0.1 AAudio diagnosis and foreground service remain available from the home screen. Choose **Open v0.2 WebRTC AEC3 sound-engine test** to run the independent test pipeline.

## What v0.2 implements

- Mic input and audio output in native AAudio at requested **48 kHz PCM16**, with real rates and channel count displayed; **fails loudly** if native rate/channel mismatch rather than claiming working AEC.
- Local import of up to 12 MB of user-owned **PCM16 WAV** (mono/stereo, 8–96 kHz); preload a looping speech fixture through the same speaker path that will eventually receive Bluetooth far-end PCM. The import currently uses an initial linear resampler; a bandlimited resampler is a future improvement.
- Existing 660 Hz diagnostic tone remains available, but **speech is required** for meaningful AEC adaptation.
- Timestamp-free preliminary render-to-capture reference queue from the **exact post-gain PCM written by the output callback**. Actual presentation time is **not yet known** and the adjustable 0–700 ms stream-delay knob must be calibrated. Reference FIFO, microphone FIFO, frame drops, stream errors and worker timing are displayed.
- Dedicated C++20 10 ms worker running the **full native WebRTC AudioProcessing Module (APM)** AEC3 + selectable NS (off/moderate/high) + AGC2 (off/on); raw bypass; optional Speex cancellation baseline if the library is bundled. A missing Speex library is reported and the output is bypassed, never silently represented as Speex processing.
- Stereo input probe requests two audio channels and verifies the actual AAudio stream format and a basic left/right difference statistic. **Stereo processing currently downmixes both channels to one AEC input.** Android may return mono despite a stereo request; camera stereo recording does not guarantee 2 physical microphones are exposed here.
- Bounded in-memory recording controlled by explicit user buttons, maximum 30 seconds; exports three synchronized **48-kHz 16-bit mono WAVs**: `*_mic_raw.wav`, `*_mic_processed.wav`, `*_farend_reference.wav`, plus `*_metadata.json`. There is no upload or automatic microphone recording.
- Two independent lock-free native single-producer/single-consumer FIFO queues. No heavy DSP, blocking locks, heap allocation or disk I/O in AAudio data callbacks. WAV writing happens in a background thread after recording stops.

## Build on Windows with Android Studio

1. Check out branch `feature/v02-live-webrtc-apm-test` (or download ZIP from GitHub by selecting that branch).
2. From PowerShell in its project root, run `powershell -ExecutionPolicy Bypass -File .\PREPARE_APM_WINDOWS.ps1` once. This downloads **pinned WebRTC APM M155 Android archive** and checks SHA-256 `0d9d3d9d801e2cfcd3b73a7296352037ac6c0b41a2632bee675c0162913e253b`.
3. Optional only: if `/data/local/tmp/libspeexdsp.so` is still available on S9+, use `powershell -ExecutionPolicy Bypass -File .\PREPARE_SPEEX_OPTIONAL_WINDOWS.ps1`. You do **not** need Speex to use WebRTC AEC3.
4. Open project folder with `settings.gradle` in Android Studio. Ensure SDK platform 34, Android NDK side-by-side and CMake are installed, Gradle 8.7 is selected (the original project intentionally omits gradle-wrapper.jar).
5. Build debug APK and install to S9+ from the **same Windows computer/signing key** as v0.1:
   `adb install -r app\build\outputs\apk\debug\app-debug.apk`
6. Open app, tap **Open v0.2 WebRTC AEC3 sound-engine test**. Grant mic permission if prompted, import speech WAV, select **WebRTC APM**, then arm.
7. Play the fixture at a moderate hardware volume. Tap **Start synchronized recording**; remain silent for 5 s, speak for 5 s, then speak while far-end plays. At about 20 s tap **Stop recording and export WAVs**.
8. The app status/logcat will show the saved prefix. Use `adb logcat -s S9SoundEngine` or pull the files under `/sdcard/Android/data/com.hfpengine.s9/files/`.

## Test strategy

**Far-end only:** play someone else's speech, remain quiet; listen to mic_raw versus mic_processed. **Near-end only:** stop playback and speak; verify voice remains clear. **Double-talk:** play a speech fixture while speaking yourself. Try NS off/moderate/high and AGC disabled first, then enabled; look for pumping/robotic artifacts. Compare **same geometry, volume and clip**. Repeat with BMW speaker via AUX while safely parked; a S9+ speaker test alone is not representative of BMW acoustic reflections.

Treat energy-only suppression as insufficient: AEC can appear strong simply by muting the user's voice. For meaningful effectiveness evaluate controlled far-end echo and near-end speech preservation during overlap. The desired under-300-ms call delay is a **goal**, not validated; the app reports local DSP frame work time, not cellular/Bluetooth/hardware transport latency.

## Limitations before declaring production-ready

- This is a **real-time app-local AEC processor**, not yet an HFP call endpoint. Processed 48-kHz mono PCM currently goes to diagnostic WAV memory, not to Bluetooth HCI. That transport is intentionally the last milestone.
- It does not yet tap **other Android Auto/OHU audio** played outside its own AAudio stream. Such sounds won't appear in its AEC reference. Future full car integration needs a common mixer/post-gain reference or audio ducking.
- Currently forces 48 kHz input/output and mono output; devices with incompatible native formats may fail to start. A proper bandlimited resampler and deeper device selection are necessary for complete USB support.
- AEC input/output callbacks are approximately paired by FIFO order. We still need measured hardware presentation/capture timestamps, adaptive delay alignment and latency calibration on S9+. The displayed delay knob is not automatically measured.
- Double-talk, ERLE, mic sound quality, CPU/deadline and stability must be assessed on real device. No claim of passed on-device tests yet.
- Stereo-mic AEC/beamforming is **not** implemented, only a two-channel capture probe/downmix.
- No root use, Magisk change, Frida HCI modification or suspect Wi-Fi tethering module is involved in v0.2.

## Upstream binary and license

Pinned candidate: `https://github.com/Sqhh99/webrtc-audio-processing/releases/tag/m155.8059.4.0`. The setup script downloads the Android arm64 binary and native C++ headers, and copies its license files into app assets. The archive's C++ API and runtime dependencies are version-specific; do not substitute another build without adapting the native wrapper and re-running CI. Speex remains optional.

## Logs

```bat
adb logcat -s S9SoundEngine HfpEngine HfpEngineNative
adb shell dumpsys media.audio_policy > s9_v02_policy.txt
adb shell dumpsys media.audio_flinger > s9_v02_flinger.txt
```
