#!/system/bin/sh
MODDIR=${0%/*}
exec >"$MODDIR/boot.log" 2>&1
[ -f "$MODDIR/device" ] || exit 0
device=$(cat "$MODDIR/device")
attempt=0
# Standby is controlled by the app's saved master switch. Never enable Bluetooth
# or start the UI here. Stop waiting after 30 minutes if standby remains off.
while [ "$attempt" -lt 120 ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] &&
     dumpsys activity services com.hfpengine.s9.local | grep -q 'isForeground=true'; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 15
done
[ "$attempt" -lt 120 ] || exit 0
# Allow the existing HFP-client module to finish its Bluetooth restart.
sleep 20
attempt=0
while [ "$attempt" -lt 6 ]; do
  dumpsys activity services com.hfpengine.s9.local | grep -q 'isForeground=true' || exit 0
  CLASSPATH="$MODDIR/helper.jar" app_process /system/bin com.hfpengine.s9.root.HfpReconnect "$device" connect >"$MODDIR/attempt.log" 2>&1
  cat "$MODDIR/attempt.log"
  grep -q 'HFP connected' "$MODDIR/attempt.log" && exit 0
  attempt=$((attempt + 1))
  sleep 15
done
