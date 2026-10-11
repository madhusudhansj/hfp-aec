# v0.2.4 microphone presets and held peaks

Both capture selectors expose all AAudio presets: Camcorder (5), Generic (1),
Voice recognition (6), Voice communication (7), Unprocessed (9), and Voice
performance (10, Android 10+). Voice performance is omitted on Android 9.
Privileged voice-call taps, remote submix, and AudioRecord-only sources are not
AAudio capture presets and are not added as options that cannot work here.

The sound-engine screen remembers its selected capture preset. Tap Arm / restart
to reopen the capture stream after selecting another preset. Requested and
AAudio-reported actual preset IDs appear in diagnostics and export metadata.
These IDs do not prove which physical mic is selected or which HAL effects run.
Voice communication may enable vendor processing; Unprocessed can be quieter.
The original first-page diagnostic controls remain available.

Raw and processed **live peak** are the absolute largest PCM sample in the latest
10 ms block. New **MAX since arm/reset** readings retain the largest sample across
every processed block, even if it occurs between UI refreshes. Reset maximum peak
meters clears session holds on the next processed block. Restarting the engine
also starts a fresh session. Stereo capture is downmixed before these meters,
so they describe the mono signal sent to DSP, not independent microphone channels.

PCM16 ranges from -32768 to +32767. Peaks use a wider integer before taking the
absolute value, so -32768 correctly reports 32768. Clipping counts now count only
the two integer rails (-32768 and +32767), not -32767. A rail value is a saturation
indicator, not by itself proof that the original analog waveform clipped.

Export metadata records `recording_raw_max_peak` and
`recording_processed_max_peak`, scanned from the actual recorded arrays. These
are recording-specific, and are unaffected by resetting the session peak holds.

The instrumentation check exercises signed PCM endpoints, known RMS, clipping
rail counts, session hold/reset, exact recording maxima, and all six presets on
the S9+. It does not open the microphone or record speech.

Verified on 12 October 2026: local app and test APK builds passed; v0.2.4 (6)
installed on the S9+; on-device tests passed all six presets, PCM peak/RMS/clipping
boundaries, maximum hold/reset, recording maximum calculation, and public Downloads
export regression checks. Actual preset gain and vendor effects remain listening
comparisons for the user; the tests do not claim equal gain across presets.

## HCI bridge next step

The repository currently contains the proposed boundary in BRIDGE_PROTOCOL.md,
but no Frida/SCO implementation. The connected phone has frida-server and DSP
libraries in /data/local/tmp, but no bridge script there. Integrating the existing
working bridge requires its source location and negotiated codec/framing details.
Do not equate a Bluetooth snoop log with a live duplex HCI transport.
