# S9 real-time sound engine — architecture and acceptance plan

**Status: design only.** This does not claim working AEC3 in the APK. Built around the existing v0.1 native AAudio microphone + speaker app, with the proven v1.5 Frida Bluetooth bridge explicitly deferred.

## Decision

Use the **full WebRTC AudioProcessing Module (APM)** on Android arm64 as primary DSP: AEC3, high-pass filter, moderate noise suppression (NS), carefully configured AGC2. Maintain no-processing bypass and SpeexDSP as comparison/fallback. RNNoise is an optional noise reduction **after** echo cancellation, not an AEC replacement.

Pin a WebRTC build, record its source revision/licenses and verify its headers/ABI before integration. The previously staged libwebrtc_aec3_jni.so is not known to export the **full APM** API; do not assume compatibility. A third-party standalone APM build offers Android archives but must be inspected and verified.

## Exact PCM architecture

Far-end test WAV (later decoded HCI SCO) → bounded PCM jitter/playback FIFO → gain/limiter → **final render PCM** → [AAudio output; time-aligned WebRTC APM reverse stream].

Built-in mic (or later USB mic) → native AAudio input → timestamped bounded FIFO → 10-ms frame assembly/resampler → **APM ProcessStream** → processed PCM ring → [WAV recorder; later encoder/HCI uplink].

WebRTC reverse processing uses **the actual post-volume PCM reaching the output buffer**, not the raw HCI arrival samples. Account for the DAC presentation time and mic capture time using frame counters and AAudio stream timestamps when available. Set the WebRTC stream delay from real measurements, not an unexplained 450-ms constant. Flush/adapt after output/input route changes.

**Critical Android Auto caveat:** AEC can only cancel signals for which it has a reference. When the BMW car speakers play other Open Headunit streams, we must also reference that final mix, or duck concurrent audio. Otherwise the other audio will still be picked up by the microphone.

## DSP thread and timing contract

* Each real-time AAudio callback only copies PCM to/from **preallocated lock-free single-producer/single-consumer rings** and increments counters. Never allocate, take locks, open files, call JNI, log frequently or process WebRTC inside an audio callback.
* An independent native high-priority worker consumes 10 ms frames, runs ProcessReverseStream on the render path and ProcessStream on the captured mic. Log status codes, frame queue depth, missed deadlines and actual compute times.
* Use 48 kHz PCM when supported (480 samples per 10-ms DSP frame), with a proper resampler for non-48-kHz device rates. Later convert processed speech to decoded SCO wideband 16 kHz or narrowband 8 kHz as negotiated. Never presume the HCI SCO payload itself is PCM.
* For mono HFP calls, start with mono mic. Test request for 2-channel microphone capture as a separate option: record channel count and actual physical microphone mapping. The Samsung camera's stereo recording does not prove independent dual-mic access is available to an ordinary Android AAudio client.
* Disable competing Android/vendor AEC/NS where possible and log any effects; double processing can damage the reference and near-end speech. Compare input presets including CAMCORDER, GENERIC and VOICE_RECOGNITION.
* Estimate algorithmic, queue and measured HAL/codec latency separately. **Goal: <300 ms conversation latency wherever practical**, targeting <80 ms p95 inside our controllable capture-to-processed pipeline. External network and controller timing are not under this APK's control.

## Testable app features (before Bluetooth)

1. Import a **user-owned, speech-heavy PCM WAV** through Storage Access Framework. Decode/resample ahead of time. Preload a bounded (15–30 s) clip; play it as far-end conversation audio with repeat/stop and a volume control. Retain the 660-Hz tone as a simple routing test only.
2. Let user select **Bypass / WebRTC APM / Speex baseline**, with quality presets for NS/AGC. Make AEC reset state visible; never pretend bypass is AEC.
3. **Record synchronized diagnostic tracks only on explicit command**: final render reference, raw mic, APM-processed mic, optional Speex-processed mic, and JSON metadata containing start times, sample rates, mic/output device IDs, physical channel count if known, APM config, underruns, drop counts, timestamps, and latency estimates.
4. Show output/capture RMS and peak, clipping count, processing deadline p50/p95, xrun counters, APM errors, reference/capture misalignment.
5. Add microphone 2-channel probe, verifying that requested 2-channel audio is **actually** returned and that channels differ meaningfully before attempting array or beamforming logic.
6. Keep playback, mic capture, DSP and recorder separated so HCI transport can be added without rewriting signal processing.

## Evaluation protocol

Use the **same playback clip, level, mic placement and input/output route** for each DSP mode.

* **Far-end only:** Play someone else's recorded speech through S9+ speaker and stay silent. Let filter converge, then listen to raw and processed mic. ERLE on *controlled far-end-only segments* = 10 log10(sum(raw^2)/sum(processed^2)); do not interpret arbitrary volume reduction as proven intelligible echo cancellation.
* **Near-end only:** Mute far-end, speak in a normal voice; listen for consonant loss, gating, metallic artifacts, AGC pumping and clipping.
* **Double-talk:** Speak at the same time as the played speech, including softer words. Prefer close-talk clean reference recorded separately for evaluation. Success requires both substantial far-end rejection and clearly intelligible near-end speech.
* **Car condition:** Re-run while parked with BMW AUX/car speakers at representative volume, AC fan off/on, and later external mic if needed. A phone loudspeaker test does not capture the car acoustic path.
* **Stress:** Sudden output volume change, silence, route change, DAC reconnect, 5-minute run, stream restart and CPU use. Capture p95/p99 processing time, drops and underruns.

No claim of success until real S9+ processed WAVs sound good in far-end-only and double-talk and the application-controlled latency has been measured.

## Milestone boundaries

* **v0.2** — exact reference PCM + speech-WAV playback + capture/metadata export, baseline bypass, optional Speex offline A/B. There is already an experimental draft PR #1 containing offline Speex WAV comparison; do **not** confuse it with a live APM engine.
* **v0.3** — packaged native APM arm64 library + 10-ms DSP worker + synchronized live capture/render + WAV export, raw/APM switching; actual on-device acoustic tests.
* **v0.3.1** — tune latency, alignment, double-talk and car-specific speaker/mic routing; test microphone stereo capability.
* **v0.4** — add authenticated local HCI bridge from existing v1.5 Frida work after AEC passes. No ROM Bluetooth.apk edits or suspect tethering Magisk changes.

## Public technical references

- WebRTC APM overview: https://android.googlesource.com/platform/external/webrtc/+/HEAD/modules/audio_processing/g3doc/audio_processing_module.md
- WebRTC AudioProcessing API: https://android.googlesource.com/platform/external/webrtc/+/master/modules/audio_processing/include/audio_processing.h
- Candidate prebuilt Android APM archive (third-party, verify): https://github.com/Sqhh99/webrtc-audio-processing/releases
- AAudio API: https://developer.android.com/ndk/guides/audio/aaudio/aaudio
- Android AudioRecord active microphones: https://developer.android.com/reference/android/media/AudioRecord
- RNNoise noise suppression: https://github.com/xiph/rnnoise

**Still unverified:** binary compatibility, 2-channel AAudio mic input on S9+ PixelOS 12L, APM CPU cost and effectiveness, playback/capture latencies. None is claimed by this document.
