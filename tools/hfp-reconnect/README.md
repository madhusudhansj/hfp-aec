# Root Bluetooth boot reconnect

The separate module described below has been superseded on the test phone by
the integrated `tools/pe12-hfpclient` v0.5 module. The Java helper is shared.
The integrated path reconnects immediately after the Bluetooth restart and
does not wait for audio-app standby. Keep the separate module disabled when
using the integrated version.

The tested Android 12 S9 ROM exposes
`IBluetooth.connectAllEnabledProfiles(BluetoothDevice, AttributionSource)`.
A root `app_process` helper can call it without binding an app service or
reading/exporting the Bluetooth database. It uses the device's existing enabled
profile policies. The initial test reconnected the paired OnePlus and dumpsys
confirmed HeadsetClientStateMachine `Connected`, audio state 0 (no call).

Build HfpReconnect.java against Android SDK 34 with javac, convert its classes
to DEX with SDK d8, and archive classes.dex into helper.jar. Install helper.jar,
service.sh and module.prop under `/data/adb/modules/s9_hfp_reconnect`, plus an
empty skip_mount file. Store the paired MAC in a root-only `device` file;
addresses are deliberately excluded from these sources.

The module waits for the local app's foreground standby, allows 20 seconds for
the existing HFP-client module to restart Bluetooth, then attempts up to six
connections at 15-second intervals. It exits when HFP is connected. It does not
enable Bluetooth, change pairing/profile policies, or place calls. It may also
connect other profiles already enabled for that phone, using the Android API.
There is no ongoing retry after the bounded boot window.

Disable `s9_hfp_reconnect` in Magisk and reboot to disable automatic reconnect.
The master switch must be enabled for boot reconnect to run; disabling standby
does not disconnect an existing Bluetooth connection.

A real reboot test on the S9+ passed: standby started with the UI closed,
the bounded retry log ended with `HFP connected`, and Bluetooth dumpsys confirmed
the OnePlus HeadsetClientStateMachine was `Connected` with audio state 0.
