# S9 HFP Engine v0.3.2

Fixes delayed boot standby on the tested rooted Android 12 Galaxy S9+.
The root boot script no longer redirects Android Binder shell-command output
to a regular file, which caused `Failed transaction (2147483646)` on this ROM.
Commands now log through pipes, and standby readiness is checked immediately.

Controlled device test, with the UI closed and standby stopped: first-attempt
foreground startup took 0.09 seconds; the Bluetooth audio bridge connected
1.408 seconds after receiver delivery. No Binder transaction errors occurred.
This verifies the startup fix after boot; full cold-boot latency remains unmeasured.

The HFP Magisk module v0.5 source also integrates paired-phone reconnection
directly after its single Bluetooth restart. The separate standby-dependent
reconnect module is superseded. On the test phone the module completed a
Bluetooth restart and reconnection in 11.43 seconds. See `tools/pe12-hfpclient`.

Install the attached APK over v0.3.1; the package/signing key are unchanged
(`com.hfpengine.s9.local`, version code 9). The original v0.1 app remains separate.
The corrected boot script is installed when root standby next starts.
Keep boot standby enabled. Microphone and playback remain closed until a call.

The APK does not install the separate HFP/Wi-Fi/reconnect Magisk modules on a
new phone. Those dependencies remain as described in the v0.3.1 release.

APK SHA-256:
`970282777b4df0166aa2b6deee816239e466833bb98ca6fc79c7f8bac5071b23`
