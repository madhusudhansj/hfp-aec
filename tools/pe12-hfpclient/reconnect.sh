#!/system/bin/sh
MODDIR=${0%/*}
LOG="$MODDIR/hfp_boot.log"
[ -f "$MODDIR/autostart" ] && [ -f "$MODDIR/device" ] || exit 0
device=$(cat "$MODDIR/device")
attempt=0
while [ "$attempt" -lt 12 ]; do
  [ -f "$MODDIR/autostart" ] || exit 0
  [ "$(settings get global bluetooth_on)" = 1 ] || exit 0
  uptime=$(cut -d ' ' -f 1 /proc/uptime)
  echo "Reconnect attempt $attempt at uptime=$uptime" >> "$LOG"
  # Tee keeps stdout as a pipe: this ROM rejects some Binder shell calls
  # when stdout is redirected directly to a regular file by the parent shell.
  CLASSPATH="$MODDIR/helper.jar" app_process /system/bin com.hfpengine.s9.root.HfpReconnect "$device" connect | tee "$MODDIR/reconnect-result.log"
  cat "$MODDIR/reconnect-result.log" >> "$LOG"
  if grep -q 'HFP connected' "$MODDIR/reconnect-result.log"; then
    echo "Reconnect confirmed at uptime=$(cut -d ' ' -f 1 /proc/uptime)" >> "$LOG"
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 3
done
echo 'Reconnect window ended; no further polling' >> "$LOG"
