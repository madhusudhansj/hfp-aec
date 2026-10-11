# S9 HFP Engine v0.3.1

Tested Android 12/rooted Galaxy S9+ build, installed alongside the original v0.1
as `com.hfpengine.s9.local` (version code 8). The attached APK uses the same
local debug signing key as the tested installation, allowing an in-place update.
This is an experimental device-specific build.

Changes include:

- Main-page sound tunables and live microphone/AEC metrics.
- Root-managed Bluetooth SCO call bridge with WebRTC AEC3 and noise suppression.
- Shared Downloads exports for diagnostic recordings.
- Boot standby recovery with bounded retries and diagnostic logs.
- Separate Magisk fixes for the S9 ROM's Wi-Fi Direct interface naming and
  paired-phone Bluetooth boot reconnection.

Verified on the S9+: two-way call audio, automatic standby with the app closed,
automatic OnePlus HFP reconnection after reboot, and Android Auto video over
Wi-Fi Direct. Microphone and speaker remain closed while waiting for calls.

## Installation and dependencies

Install `S9-HFP-Engine-v0.3.1.apk` and grant microphone/root permissions. Keep
the boot standby switch enabled. The original v0.1 application is separate.

The APK installs its standby boot fallback after root is granted. HFP-client
profile enablement, Wi-Fi Direct compatibility, and boot reconnection also
depend on the separate Magisk modules already installed on the tested phone;
installing the APK alone on another phone does not install those modules.
Their sources and setup notes are in `tools/s9-wifi-p2p-overlay`,
`tools/hfp-reconnect`, and `docs/V030_CALL_BRIDGE.md`.

AAudio input timestamps remain unavailable on this ROM: the automatic external
delay hint falls back to an unmeasured 80 ms, while AEC3 tracks internal alignment
independently. Long-duration thermal behavior has not been established.

APK SHA-256:
`b25cbfe81f43bd51ac00a66d3c097a9540ece91b39ef3c3812f39fb9ef8d1c51`
