#!/system/bin/sh
# Installed by the root bridge. Bounded boot recovery; no UI or idle audio.
package='@@PACKAGE@@'
logfile="/data/adb/s9-hfp-${package}-boot.log"
# Android Binder shell commands on this ROM reject regular-file stdout.
# Keep their stdout connected to a pipe, and log plain shell messages separately.
: >"$logfile"
log() { echo "$*" >>"$logfile"; }
log 'Waiting for boot completion and first unlock'
attempt=0
while [ "$attempt" -lt 900 ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] &&
     [ "$(am get-started-user-state 0 2>/dev/null)" = RUNNING_UNLOCKED ]; then
    # Package and device-idle services may lag sys.boot_completed on this ROM.
    # Retry briefly instead of losing the entire boot on one early failure.
    retry=0
    while [ "$retry" -lt 6 ]; do
    log "Standby startup attempt $retry at uptime=$(cut -d ' ' -f 1 /proc/uptime)"
    # A shell-sent boot broadcast lacks Android's real boot FGS exemption.
    # Grant only a short startup window, not a permanent battery exemption.
    cmd deviceidle tempwhitelist -d 20000 "$package" 2>&1 | tee -a "$logfile"
    # Receiver checks the saved master switch. Repeated starts are idempotent.
    am broadcast --user 0 -a android.intent.action.BOOT_COMPLETED \
      -n "$package/com.hfpengine.s9.BootReceiver" 2>&1 | tee -a "$logfile"
    check=0
    while [ "$check" -lt 5 ]; do
      if dumpsys activity services "$package" | grep -q 'isForeground=true'; then
        log "Standby foreground service confirmed at uptime=$(cut -d ' ' -f 1 /proc/uptime)"
        exit 0
      fi
      sleep 1
      check=$((check + 1))
    done
    retry=$((retry + 1))
    done
    log 'Standby not running (disabled, package absent, or startup failed)'
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 2
done
