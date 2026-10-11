# v0.3.0 call bridge and main controls

The main Activity is now the call-engine control page: boot standby/master switch,
USB/internal microphone pathway, all AAudio capture presets, stereo probe,
processor, noise suppression, adaptive AGC2, automatic/manual stream-delay hint,
peak reset, and compact live meters. Sound Engine Test retains fixture playback,
recording/export, and detailed diagnostics. Settings are shared and persisted.
Capture-device changes take effect on the next engine start; DSP settings can be
applied while processing. Arming diagnostics pauses standby. Return to the main
page after stopping diagnostics to resume enabled standby.

## Build

Run PREPARE_APM_WINDOWS.ps1 and PREPARE_FRIDA_WINDOWS.ps1 (Python 3 with lzma).
The official ARM64 Frida injector is an ignored build dependency, not source in Git.
Frida release: https://github.com/frida/frida/releases/tag/17.2.3
Executable SHA256: 795e05b765a68c9272e5abc0bac9536a4ee74799aff2f893fa69dad2a580b8e7
Archive SHA256: f74ce5b1c7a587d4e19e9541d2c0c5131e0e645b0bcd9c4c32f208a9c500656c
Upstream source: https://github.com/frida/frida-core/tree/17.2.3 and
https://github.com/frida/frida-gum/tree/17.2.3 . License is bundled in bridge assets.
The local APK uses com.hfpengine.s9.local to preserve the separately signed v0.1.

## Runtime

Standby requests root and copies its injector/script to an app-specific root-only
/data/local/tmp/s9-hfp-PACKAGE-SESSION directory. It attaches to com.android.bluetooth.
No manual PC Frida session is needed. Disabling standby detaches only this helper;
no system APK or ROM files are modified. Reattach retries if Bluetooth restarts.
Idle standby opens no AAudio streams. It maintains an authenticated loopback socket.

Only the verified S9+ /system/lib64/libbluetooth.so build is supported:
8775751177d49ddd74037f7341fbafc2. Hardcoded function addresses are guarded by this ID.
The Enhanced Accept SCO command 0x043e is patched only when the APK is connected,
paths are vendor 01/01, and host PCM is signed 16-bit, 16 kHz, linear PCM. Unsupported
formats are left unchanged; this does not decode raw mSBC/CVSD host payloads.
Host coding fields follow AOSP esco_parameters.h:
https://android.googlesource.com/platform/system/bt/+/android-9.0.0_r7/device/include/esco_parameters.h

First SCO PCM activates EngineService. Incoming 240-byte packets are interpolated
16->48 kHz with a streaming 63-tap FIR, played by the APK, and the actual samples
submitted by the output callback (including underrun silence) feed the APM reverse
stream. This is not a measurement of physical speaker output. APM processes 480
samples/10 ms, and FIR low-pass decimation produces 16 kHz uplink. SCO TX remains
paced by the incoming controller SCO clock. No network I/O or APM is performed
inside the Bluetooth callback. Frida queues are mutex-protected and bounded; APK
queues have separate single producers/consumers and trim backlog on consumer sides.

The optional HCI disconnect hook ends the call promptly; a 1-second missing-SCO
watchdog and 1.2-second APK watchdog also close audio. No received audio is recorded
automatically. Old OHU mic/player Frida scripts must not also run: this APK replaces
both injected audio paths while OHU itself continues Android Auto.

## Delay and remaining validation

Output requests two hardware bursts (8 ms reported on this S9+). Software queues
are bounded and trim excess audio, reported separately from underruns. There is no
fixed 450 ms reference pre-delay. AEC3 keeps internal alignment enabled.

Input timestamps still return AAUDIO_ERROR_INVALID_STATE while output timestamps
work. Auto external hint therefore still falls back to an explicitly unmeasured
80 ms; it is not the internal AEC delay or measured end-to-end latency. The root
bridge does not repair the capture HAL's timestamp support. Capture-path comparisons
and AudioRecord timestamp experiments remain future work. Continuous clock drift
compensation, adaptive jitter buffering, and focus negotiation with OHU are also
not implemented. Queue trimming bounds latency but is not smooth drift correction.

Disabling/crashing the bridge during an HCI-routed call does not switch that already
established SCO link back to vendor PCM. End/reconnect that call to restore its
normal routing. Changes only affect future SCO establishment.

Validation on the S9+: local build; strict PCM command guard; native FIR passband,
>40 dB alias rejection, interpolation DC gain; malformed PCM rejection; existing
exports and peak meters; actual app-managed root attach and clean detach with idle
streams closed; background synthetic call-mode capture/APM/playback processing with
zero APM/AAudio errors. No real call or OHU audio-focus coexistence was verified.
Silent synthetic test AEC delay/ERLE values do not validate acoustic cancellation.

Device tests use ExportVerification: default, -e bridge true (root idle attach),
-e call true -e background true (brief silent synthetic call, no recording), and
-e transport true (authenticated socket and repeated call lifecycle). Tests stop
all audio and remove synthetic export data. Run node tests/bridge-format.cjs for
host-format validation. Real incoming/outgoing calls, hangup, route changes, and
OHU coexistence still require a user-observed test call.

