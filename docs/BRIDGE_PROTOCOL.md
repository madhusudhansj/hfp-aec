# HCI / APK PCM protocol v0.3.0

This replaces the earlier unimplemented design. The current prototype follows the
working Frida transport with one loopback-only TCP connection on 127.0.0.1:38629.
A filesystem AF_UNIX transport with peer-UID validation remains a future improvement.

The APK listens. The injected Bluetooth client sends a random 32-character token
plus LF (33 ASCII bytes), generated on every standby start and staged privately.
The APK compares it in constant time and returns one ACK byte 0x06. The helper
must receive the ACK before allowing any SCO route changes. This protects against
accidental local clients, not another process with root access. No Wi-Fi listener.

Bluetooth -> APK: fixed 248-byte messages:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 2 | ASCII S9 |
| 2 | 1 | 1 = PCM; 2 = SCO ended |
| 3 | 1 | Version 1 |
| 4 | 4 | Little-endian unsigned session ID |
| 8 | 240 | Signed little-endian PCM16 mono at 16 kHz; ignored for end |

APK -> Bluetooth: exactly 240 PCM bytes for each PCM message. End messages have no
response. Pending startup and uplink underruns return silence. The helper buffers
partial TCP reads; APK partial-frame state survives socket timeouts. Invalid framing
closes the connection and its owned call engine. Session change restarts APM/audio.
No per-frame sample timestamps or sequence numbers are included yet; frame and queue
accounting are local. Transport latency is not a hardware or acoustic delay estimate.

The current controller configuration supplies decoded host PCM. Both host input and
output format/rate/sample width/sign are checked on SCO setup before rerouting.
This is not a generic decoder for CVSD or mSBC HCI payloads. Only the pinned S9+
Bluetooth library build and Enhanced Accept SCO setup path are supported.

See V030_CALL_BRIDGE.md for root deployment, timing, activation, and limitations.
