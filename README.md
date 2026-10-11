# S9 HFP Engine — v0.1 Android Studio source

**Hardware target:** Samsung Galaxy S9+ SM-G965F (arm64), rooted PixelOS / Android 12L.

## What this milestone actually implements

- Small diagnostic activity; **two headless foreground services**.
- `StandbyService`: optional passive boot service. Starts on `BOOT_COMPLETED` when enabled. Does **not** open the microphone or handle calls yet.
- `AudioService`: user-armed microphone foreground service that runs while UI is closed. It uses an actual **native C++ AAudio** microphone capture callback and speaker/USB DAC output callback. It does not store or send microphone audio.
- Device auto-selection: prefer USB input and USB output independently, with built-in fallback; automatically restarts AAudio streams when the devices list changes.
- Select a capture preset: CAMCORDER (5), GENERIC (1), VOICE_RECOGNITION (6).
- Optional quiet ~660 Hz diagnostic tone; continuously shows microphone RMS, peak, AAudio device IDs, frame counters, sample rates, callback burst, buffer sizes, states and best-effort stream timestamps.
- No Frida, no Bluetooth injection, no AEC3/WebRTC DSP **in this first build**. These are future milestones.

**This is a source project, not a compiled APK.** No Android SDK/NDK/Gradle toolchain was available in the creation environment, so **the project could not be compiled or run on the actual S9+ here**. It was checked for syntax and project structure to the extent possible.

## Build using Android Studio on Windows

1. Install Android Studio with JDK 17 (Studio's embedded JDK is fine).
2. In SDK Manager install **Android SDK Platform 34**, Android SDK Build Tools, **NDK (Side by side)** and **CMake**.
3. Open **the folder containing `settings.gradle`** as a project in Android Studio.
4. Let Gradle sync. The project uses AGP 8.5.2 / Gradle 8.7; Android Studio may download them. **The source archive intentionally does not include Gradle's executable wrapper JAR**; if Studio asks about a missing Gradle wrapper, select an installed Gradle 8.7 distribution under Settings → Build Tools → Gradle or run `gradle wrapper --gradle-version 8.7` if `gradle` is on PATH.
5. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The output is `app/build/outputs/apk/debug/app-debug.apk`.
6. On Windows, from the project directory:

   ```bat
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n com.hfpengine.s9/.MainActivity
   ```

7. Grant microphone permission. Tap **Enable boot standby** to opt into the boot-persistent *idle* monitor. Tap **Arm native audio** while the Activity is open. You can now close the Activity; the active foreground service continues.
8. Tap **Play quiet reference tone** to verify speaker route and watch mic RMS. Prefer using the internal speaker initially. Test USB DAC and USB mic only after the basic path works.
9. Check the ongoing notification; avoid battery optimization for this app if PixelOS aggressively stops services.

Debug from Windows:

```bat
adb logcat -s HfpEngine HfpEngineNative AudioFlinger AudioPolicyManager
adb shell dumpsys activity services com.hfpengine.s9
adb shell dumpsys media.audio_policy
```

## Important Android 12L restriction

Android 12+ allows a foreground service to start at boot in some circumstances, but **a microphone-using foreground service started in the background can lack permission to open the microphone**. This prototype therefore starts **passive standby at boot**, and requires the user to arm the mic engine via the visible Activity after boot. Once armed, that foreground service may continue after the Activity is closed, including during calls, provided Android does not kill it.

A truly unattended, mic-capturing post-boot start needs a separately validated privileged system integration or other authorized exemption. We will not claim this works just because the `BOOT_COMPLETED` receiver fires.

## Audio design notes

- Input and output device selection are **independent**. If a USB DAC lacks a microphone, built-in mic remains available. If a USB mic is later connected, it becomes the preferred input. Android IDs change on reconnect and the service rescores devices rather than hardcoding IDs.
- The app requests a low-latency AAudio callback route and lets each device choose its own native sample rate. Actual mode/rate/buffer information is reported; low-latency isn't guaranteed.
- The tone isn't an echo-cancellation test; it's a **route and level diagnostic**. Do not judge HFP call audio or WebRTC quality from it.
- Because there is no AEC currently, **do not create a loopback from mic to speaker**.
- Active microphone capture is explicit and indicated by the ongoing FGS notification. No hidden recording, background file capture or raw BT operations exist in v0.1.

## Design for v0.2 and v0.3

Read `docs/NEXT_STEPS.md` and `docs/BRIDGE_PROTOCOL.md`. The planned native audio engine has separate downlink/playback reference and uplink/capture paths; when the WebRTC AudioProcessing library is integrated, capture and render are split into 10-ms processing blocks. The privileged SCO bridge remains separate from the ordinary APK.
