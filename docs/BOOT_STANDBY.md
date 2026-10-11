# Root boot recovery (0.3.1)

Android's normal BOOT_COMPLETED receiver remains the primary startup path.
The root bridge also installs a package-specific Magisk `service.d` script
from `assets/bridge/boot-standby.sh` whenever standby attaches.
It waits up to 30 minutes for boot completion and user 0 to unlock, gives
the package a 20-second temporary power allowlist window, invokes its explicit
boot receiver, and exits. No Activity opens. The receiver checks the saved
`boot_standby` preference, so the master switch remains authoritative.

The temporary exemption is necessary because a shell-generated BOOT_COMPLETED
broadcast does not carry the framework's real boot foreground-service exemption.
A manual receiver test without it produced ForegroundServiceStartNotAllowedException.
Standby starts the root bridge only; microphone/playback streams start on SCO.
The initial normal boot delivery failure has not been established from retained logs.

Root script: `/data/adb/service.d/s9-hfp-<applicationId>.sh` (root-only mode 700).
It exits if the package is absent. To remove the fallback entirely, delete this
specific script; disabling the app switch prevents it from starting standby.
Real reboot startup still requires a device test after installation.
