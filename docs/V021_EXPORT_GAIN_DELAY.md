# v0.2.1: accessible recordings, adaptive gain, and delay diagnostics

On Android 10+, Stop / retry export publishes all three WAVs plus JSON through
MediaStore into **Downloads / S9 HFP Tests**. No broad storage permission is needed.
Files by Google can browse these files under Internal storage / Download / S9 HFP Tests.
Native WAV writing uses private staging; successfully published staging files are
removed. If publishing fails, staging is retained and the button retries publication.
A pending staged export survives service/process restart. Copy earlier exports to
Downloads copies completed v0.2 exports from this app's former Android/data folder;
it retains their originals and avoids copying already migrated sets again.

The recording flag now survives a second press of Start Recording. Export and
native controls run on a serial background executor. UI status uses cached native
statistics so writing WAVs cannot block its main thread on the native mutex.

## Quiet microphone

AGC2 means automatic gain control, version 2. Previously only its outer enabled
flag was set: this pinned WebRTC build defaults adaptive digital gain to disabled
and fixed gain to 0 dB. The checkbox now enables the adaptive digital controller,
with 6 dB initial gain, a 30 dB maximum, and 6 dB headroom. This amplifies processed
speech after echo cancellation and noise suppression, with WebRTC's limiter.
It does not increase hardware microphone gain or alter the raw WAV.

Raw/processed last-block RMS in dBFS, peaks, and processed clipping counts appear
live. Less-negative RMS values are louder. These meters cannot prove intelligible
noise suppression or echo cancellation: compare raw/processed speech recordings.
Try Generic or Voice recognition versus Camcorder if raw capture is still quiet;
the physical mic selection and vendor processing depend on the phone's HAL.

## Delay

The previous 80 ms was an unmeasured manual default. Auto is now the initial choice.
The worker uses monotonic AAudio capture/presentation timestamps, extrapolated to
the first samples of the paired PCM blocks, to estimate WebRTC's stream delay:

`delay = (render time - reverse call time) + (capture call time - capture time)`

Because the processing calls are adjacent, their times approximately cancel. The
implementation uses render sample presentation time minus capture sample time,
checks a plausible range, and bounds the applied delay to 0–500 ms. If timestamps
are unavailable, out of range, or FIFO continuity is lost, the UI explicitly reports
an **80 ms fallback**, rather than labeling it a measurement. Restart after gaps.
Manual calibration remains available by unchecking Auto. The UI also shows AEC3's
internal delay estimate when WebRTC supplies one. Stream delay is not total call
latency, and hardware timing alone does not prove acoustic echo alignment.

Metadata records applied delay, selected delay (-1 for Auto), available timestamp
estimate (-1 when unavailable), adaptive gain selection, and missing reference blocks.
Long-term clock drift and recovery after dropped samples remain future work.

## Verification

`ExportVerification` is an Android instrumentation runner using synthetic PCM WAVs.
It checks actual public MediaStore paths, pending flags, byte-exact WAV/JSON reads,
incomplete-set rejection, preservation of private sources after failure, and native
library loading. It removes its synthetic public files. It does not open the mic.
An optional `-e migrate true` copies the user's earlier completed exports through
the same migration action. Acoustic gain and echo quality still require listening
tests on the S9+.

Verified locally on 11 October 2026: application and instrumentation APK builds
passed; version 0.2.1 (3) installed as `com.hfpengine.s9.local`; the on-phone runner
passed public Downloads paths, completed pending flags, byte-for-byte reads,
incomplete-set rejection, retained private originals, and native-library loading.
No earlier completed recording sets were found to migrate. The test did not open
the microphone, so real speech gain, hardware delay estimates, and acoustic quality
remain to be checked during the next user-controlled capture.
