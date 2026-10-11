# Root boot recovery (0.3.2)

Android's normal BOOT_COMPLETED receiver remains the primary startup path.
The root bridge also installs a package-specific Magisk `service.d` script
from `assets/bridge/boot-standby.sh` whenever standby attaches.
It waits up to 30 minutes for boot completion and user 0 to unlock, gives
the package a 20-second temporary power allowlist window, invokes its explicit
boot receiver, and confirms a foreground service, retrying up to six times
with five one-second service checks per attempt before exiting. The boot/unlock
condition is checked every two seconds. No Activity opens. The receiver checks the saved
`boot_standby` preference, so the master switch remains authoritative.

The temporary exemption is necessary because a shell-generated BOOT_COMPLETED
broadcast does not carry the framework's real boot foreground-service exemption.
A manual receiver test without it produced ForegroundServiceStartNotAllowedException.
Standby starts the root bridge only; microphone/playback streams start on SCO.
The initial normal boot delivery failure has not been established from retained logs.

Root script: `/data/adb/service.d/s9-hfp-<applicationId>.sh` (root-only mode 700).
The bounded retries also exit if the package is absent or standby is disabled.
Diagnostics overwrite `/data/adb/s9-hfp-<applicationId>-boot.log` each boot.
To remove the fallback entirely, delete this
specific script; disabling the app switch prevents it from starting standby.
The 0.3.1 fallback inherited regular-file stdout via `exec >logfile`.
On this ROM the deviceidle/activity Binder shell commands then failed with
`Failed transaction (2147483646)`, leaving startup to the normal boot broadcast.
0.3.2 pipes those commands through tee and logs shell messages separately.
No service-wide output redirection is used. Uptime is recorded at the attempt
and foreground confirmation to measure startup independently of wall-clock changes.

Controlled verification on the S9+: with standby stopped and the UI closed,
the script was run with its parent stdout/stderr redirected to a regular file.
Attempt 0 began at uptime 333.78 and foreground confirmation occurred at 333.87.
The bridge connected 1.408 seconds after receiver delivery. No Failed transaction
errors occurred. A cold reboot is still needed to measure power-on latency.
