# v0.2.2 live AEC3 monitor

The sound-engine screen now has a dedicated panel above its controls, refreshed
about once per second. Native WebRTC statistics are sampled by the DSP worker,
and the UI receives cached strings through the background control executor.

The panel reports:

- The stream delay actually sent to APM, including whether it is manual,
  estimated from hardware timestamps, or an unmeasured fallback.
- The hardware stream-delay estimate separately from AEC3's internal delay.
- Delay median and standard deviation when supplied by WebRTC.
- Echo return loss enhancement (ERLE, dB) and echo return loss (ERL, dB).
- Residual echo likelihood and divergent-filter fraction, as percentages.
- The raw-to-processed level difference, explicitly including noise suppression
  and automatic gain; it is not labeled as isolated AEC gain correction.
- Reference gaps, sample drops, APM/AAudio errors, and the age of the last sample.

Unavailable optional statistics say **not available**, rather than displaying
zero. Statistics are cleared when the processor configuration changes. Bypass
and Speex modes label AEC3 inactive, and a stopped engine shows an inactive panel.
Internal filter coefficients are not exposed by the pinned public APM API and
are not invented as correction values. Echo metrics require representative
far-end speech and are not by themselves proof of near-end speech quality.

Includes the v0.2.1 public Downloads export, adaptive AGC2 fix, timestamp delay
diagnostics, and recording/export state fixes. The on-device export test also
checks that the new monitor JNI entry point loads and reports inactive correctly.
