# v0.2 SpeexDSP offline echo diagnostic (experimental)

This is a **record, process, and compare** implementation. It does **not** yet deliver real-time AEC to the HFP transmitter. It preserves the existing v0.1 microphone and tone foreground service and does not change ROM, Magisk modules, Bluetooth.apk, or HCI hooks.

## One-time library staging

The repository deliberately does not include the previously built ARM64 libspeexdsp.so. Use the known library **already staged on the rooted S9+**:

1. Connect S9+ over USB with ADB root `su`.
2. Run `PREPARE_SPEEX_LIBRARY_WINDOWS.bat` in the project root.
3. Verify `app/src/main/jniLibs/arm64-v8a/libspeexdsp.so` exists.
4. Build the debug APK in Android Studio (NDK/CMake installed); install with `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

The app loads `libspeexdsp.so` from its installed native library directory using `dlopen()`. An inaccessible or non-ARM64 binary produces raw/reference WAVs but no processed WAV. No other system partition/library modification is required. Make sure this library build exports `speex_echo_state_init`, `speex_echo_state_destroy`, and `speex_echo_cancellation`.

## Test on S9+

1. Arm native audio and enable the quiet reference tone at moderate volume. Use S9+ built-in speaker and built-in mic for the first test; avoid USB DAC and headphones during echo testing.
2. Set **Speex reference delay** (start with 100 ms; it is an adjustable *trial*, not a calibrated constant).
3. Tap **Start 20s AEC recording**. The app records input/output callback samples into bounded RAM buffers with no disk IO or DSP in callbacks.
4. During ~20 s, include quiet speech, tone-only, and speech-over-tone portions. Tap **Finish / Export WAV comparison** at the end.
5. Read the resulting file prefix under the app's external files directory, or from `adb logcat -s HfpEngine` when finished. Transfer the three files using `adb pull /sdcard/Android/data/com.hfpengine.s9/files/` (on many Android 12 devices ADB can read that path).

Three files are exported using identical prefix: `*_raw.wav` (mic capture), `*_reference.wav` (output callback signal offset by configured delay), and `*_speex.wav` (Speex echo-cancelled mic). All are 16 kHz, mono, PCM16.

If finish runs before Speex is loaded, the raw and reference files still provide useful evidence. The implementation deliberately refuses to pretend AEC worked in that case.

## Technical limitations

- Processing happens after capture, **not live inside AAudio callbacks**. This is intentional: we first determine audio fidelity and delay without destabilizing the working app.
- The reference is the final *callback-supplied* tone PCM. It is more faithful than Bluetooth packet arrival audio, but the output callback is not an actual loudspeaker presentation timestamp; buffering/routing latency must be calibrated.
- First callback monotonic time is only an approximation of capture/playback timing. The adjustable reference delay compensates some mismatch.
- Simple linear conversion handles devices with native sample rates other than 16 kHz and is not a production-grade anti-aliasing resampler. Prefer 48-kHz native streams for this initial diagnostic.
- Speex filter is 10-ms frames, 100-ms filter tail; a longer echo tail or nonlinear residual suppressor may be needed. A continuous 660-Hz tone is insufficient for reliable AEC adaptation; next benchmark should use speech-band excitation.
- Recording buffers are bounded to requested duration plus two seconds. Pressing Finish soon after 20 seconds is recommended. Each pass exports at most 60 seconds. No audio is uploaded.
- The library is not included in GitHub; use the existing local S9+ build. The code changes have **not** yet been compiled or validated on an actual Android device.

**Next:** After raw and Speex WAVs are verified, integrate a native WebRTC AEC3 library with a known ABI, and then add an asynchronous real-time AEC processing worker with the HFP bridge. Avoid DSP, locks, and allocations in audio callbacks.
