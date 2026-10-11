# Proposed HCI ↔ Audio Engine boundary (NOT IMPLEMENTED IN V0.1)

Control plane should use a local AF_UNIX socket with explicit allowed peer UID(s). Do not expose this on Wi-Fi, TCP or public abstract sockets. Root process validates its connected Android service before accepting audio packets or route commands.

Control messages (design):

- `HELLO`: protocol version, sample format, supported bridge codec(s).
- `HFP_CONNECTED`: SCO connection handle, negotiated codec and sample rate; transition to standby-for-call.
- `HFP_AUDIO_ACTIVE`: begin playback and capture pipeline, synchronize playback reference.
- `HFP_DISCONNECTED`: stop stream, flush ring buffers, clear AEC state.
- `ROUTE_CHANGED`: output device/capture ID changed, flush and re-estimate delay.
- `STATS`: dropped packets, buffer depth, underruns, audio delay, mic RMS and clipping.

For PCM frames, a concrete v0.2 packet format needs to specify little-endian lengths, sequence number, sample timestamp, sample rate, channel count, channel format and maximum payload. A bounded ring buffer should always have an overflow strategy. Uplink SCO packet timing remains the responsibility of the privileged bridge.

**Crucial codec caveat:** controller-to-host SCO/eSCO data may carry CVSD or mSBC encoded frames, not necessarily generic signed 16-bit PCM. The bridge must decode before entering the DSP pipeline and encode on uplink using negotiated codec and packet framing.

No raw HCI interception, Bluetooth system modifications, or SCO packet injection are present in this release.
