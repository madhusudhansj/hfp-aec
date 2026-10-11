# Roadmap and acceptance checks

## Stage 1 — v0.1 (this source project)

- [ ] Source compiles with Android Studio for arm64-v8a on Windows.
- [ ] Install and launch on rooted S9+ PixelOS Android 12L.
- [ ] Grant microphone permission; arm service; close Activity and confirm notification persists.
- [ ] Confirm RMS responds to speech; test quiet tone; check `AAudio error callback` remains 0.
- [ ] Compare CAMCORDER, GENERIC, VOICE_RECOGNITION at same position.
- [ ] Reconnect USB DAC while service running; output route reopens (check actual device IDs).
- [ ] Plug in compatible USB microphone; input auto-switches and capture responds.
- [ ] Reboot with boot standby enabled: standby notification returns, **mic remains disarmed** until explicitly armed again.

## Stage 2 — native WebRTC APM

This project does **not** bundle `libwebrtc` or pretend our existing `libwebrtc_aec3_jni.so` exposes the full AudioProcessing API. We first need to build/vend a matching arm64 WebRTC APM or implement a carefully verified JNI boundary to a compatible binary.

Target native internal DSP design:

  bluetooth downlink -> codec decoder -> jitter buffer -> PCM render queue -> playback
                                                     |                    |
                                                     |            hardware presentation timestamp
                                                     -> WebRTC reverse stream

  microphone callback -> FIFO -> 10ms PCM capture frames -> WebRTC capture stream
                      -> limiter/AGC -> HFP rate conversion -> codec -> HCI bridge uplink

Requirements:
- No locks, heap allocation, JNI or DSP calls in AAudio callbacks.
- FIFOs and processing workers are separate; overflow policy and telemetry are explicit.
- Use the **same final playback samples** as the AEC render reference.
- Use Android AAudio timestamps + measured playback/capture queues for delay alignment; do not treat packet arrival as DAC playback time.
- Resample from native device rate to WebRTC internal processing rate and back; resample HFP boundary to CVSD 8k or mSBC 16k as negotiated.
- Avoid two competing AEC/NS pipelines on the same microphone; compare presets and effect attachment.
- Provide raw and processed audio recording **only via explicit diagnostic action**, not by default.
- Evaluate double-talk near-end preservation separately from echo-only attenuation.

## Stage 3 — existing SCO/HCI bridge

- Keep our **already-working rooted Frida routing trick** initially, as a separate privileged experiment.
- Decode actual HCI SCO/eSCO packet transport; do NOT assume HCI payload is always PCM.
- Add a local Unix-domain socket with peer UID verification for control, and shared-memory queues for PCM if necessary. Start with a clearly framed socket protocol (docs/BRIDGE_PROTOCOL.md).
- Link start/stop to confirmed HFP AUDIO_CONNECTED events; stop/flush on disconnect.
- Never allow Android SCO's original uplink and our replacement uplink to both write simultaneously.
- Validate HFP signalling, microphone and audio paths in order; test without a real call first, then with calls.

## Stage 4 — stable low latency and USB

- Measure the real headset/DAC presentation delay and microphone capture delay with both internal and USB devices.
- Tune AEC3 for the BMW environment while parked before driving.
- Prioritize clear double-talk and undistorted near-end voice over numerical echo attenuation.
- Later consider supervised root helper for unattended restart at boot, with visible notification/clear opt-in and explicit mic-access boundaries.
