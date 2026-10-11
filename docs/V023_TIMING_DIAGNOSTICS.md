# v0.2.3 timing and metric availability

## Fix

The worker previously replaced a temporarily unavailable render-reference block
with silence and permanently disabled hardware estimates after that event.
It now waits until both rings contain a full block. Hardware timestamp failures
are retried every 100 ms of processed audio, with separate input/output status
codes and range/drop diagnostics in the monitor. Sample drops still invalidate
position mapping until restart; they are not silently treated as valid timing.

AEC3 is explicitly configured for its internal delay estimator. The monitor labels
the APM input as an **external stream-delay hint**, separately from AEC3's internal
alignment estimate. The internal estimate is not fed back into the external hint:
these values represent different timing information. Fallback remains an explicit
unmeasured 80 ms hint when hardware capture timing is unavailable.

Residual echo likelihood requires a separate echo detector attached through
BuiltinAudioProcessingBuilder.SetEchoDetector. This app does not attach one.
The pinned public statistics structure includes divergent-filter fraction, but
the AEC3 statistics path does not populate it in the current build. The monitor
now explains these limitations rather than showing an unexplained blank value.
Neither missing field by itself means echo cancellation is disabled.

## Actual S9+ verification, 11 October 2026

Local application/test APK builds passed. The updated app installed as
`com.hfpengine.s9.local`, version 0.2.3 (5). Public Downloads regression checks
and inactive-monitor checks passed.

The optional instrumentation argument `-e live true` starts a visible activity and
foreground audio engine briefly, with speaker playback off and no mic recording.
The engine processed paired blocks and sampled its AEC3 statistics. Observed:

- Input timestamp: `AAUDIO_ERROR_INVALID_STATE`; output timestamp: `AAUDIO_OK`.
- Hardware estimate unavailable; external hint 80 ms, explicitly unmeasured.
- Internal AEC3 delay reported 60 ms during this silent-playback test. This is
  evidence that internal statistics work, not a validated acoustic delay.
- Reference gaps 0, dropped input/render samples 0/0.
- APM errors 0, AAudio stream errors 0.

The test stops the microphone engine afterwards. No claim is made that capture
timestamps can never become available on another route, preset, or later run.
No echo-rejection or double-talk quality claim follows from silent playback.
