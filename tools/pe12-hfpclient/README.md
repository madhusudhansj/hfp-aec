# HFP client boot module v0.5

Source of the tested S9+ `pe12_hfpclient` Magisk module, with reconnect moved
directly after its single successful Bluetooth restart. This removes the old
dependency on app standby/unlock, 15-second standby polling, and the additional
20-second reconnect delay. HFP Engine startup remains independent.

The existing device/ROM checks, Bluetooth-off preference, overlay checks and
OFF/ON recovery safeguards are retained. The unnecessary three-second wait
before applying the overlay was removed; the verified three-second Classic
Bluetooth shutdown settling time remains. Boot completion is still awaited.

`reconnect.sh` begins immediately after restart_bt.sh confirms adapter ON and
HeadsetClientService available. It checks every three seconds for at most twelve
iterations, skips a connection already in progress, and exits on success.
No additional radio restart or indefinite polling is introduced. Reconnection
also depends on the paired phone being available and accepting the request;
10–15 seconds from power-on cannot be guaranteed by this module.

Install these scripts and module.prop into the existing module directory,
compile `tools/hfp-reconnect/HfpReconnect.java` into helper.jar, and store the
paired address in the root-only `device` file. Keep the existing `autostart`
marker. Disable the old `s9_hfp_reconnect` module to avoid duplicate attempts.
The module's autostart/Magisk enable switch now controls Bluetooth reconnection,
independently of the audio app's boot-standby switch.

`hfp_boot.log` includes attempt/confirmation uptime for measuring actual latency.
The installed v0.5 test completed its overlay check, Bluetooth OFF/ON restart,
and HFP reconnection in 11.43 seconds (uptime 407.00 to 418.43). The first
connect request started at uptime 412.90; connection was confirmed at 418.40.
Bluetooth dumpsys confirmed the OnePlus HFP state Connected, audio state 0.
This measures a module run on an already booted phone, not power-on latency;
the revised cold-boot path remains to be verified.
Original v0.4 service.sh/module.prop are backed up on the tested phone as
service.sh.v04 and module.prop.v04. To revert, restore those files and re-enable
the separate reconnect module, then reboot.
