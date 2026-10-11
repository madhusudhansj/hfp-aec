#!/system/bin/sh
# Installed by the root bridge. Bounded boot recovery; no UI or idle audio.
package='@@PACKAGE@@'
exec >"/data/adb/s9-hfp-${package}-boot.log" 2>&1
echo 'Waiting for boot completion and first unlock'
attempt=0
while [ "$attempt" -lt 360 ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] &&
     [ "$(am get-started-user-state 0 2>/dev/null)" = RUNNING_UNLOCKED ]; then
    # Package and device-idle services may lag sys.boot_completed on this ROM.
    # Retry briefly instead of losing the entire boot on one early failure.
    retry=0
    while [ "$retry" -lt 6 ]; do
    echo "Standby startup attempt $retry"
    # A shell-sent boot broadcast lacks Android's real boot FGS exemption.
    # Grant only a short startup window, not a permanent battery exemption.
    cmd deviceidle tempwhitelist -d 20000 "$package"
    # Receiver checks the saved master switch. Repeated starts are idempotent.
    am broadcast --user 0 -a android.intent.action.BOOT_COMPLETED \
      -n "$package/com.hfpengine.s9.BootReceiver"
    sleep 10
    if dumpsys activity services "$package" | grep -q 'isForeground=true'; then
      echo 'Standby foreground service confirmed'
      exit 0
    fi
    retry=$((retry + 1))
    done
    echo 'Standby not running (disabled, package absent, or startup failed)'
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 5
done
