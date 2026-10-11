#!/system/bin/sh
# Magisk late_start, once per boot. Opt-in using the autostart file.
MODDIR=${0%/*}
LOG="$MODDIR/hfp_boot.log"
[ -f "$MODDIR/autostart" ] || exit 0

I=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$I" -lt 120 ]; do
  sleep 2
  I=$((I+1))
done
if [ "$(getprop sys.boot_completed)" != 1 ]; then
  echo 'v0.4: boot_completed never reached 1; no action' >> "$LOG"
  exit 0
fi
[ -f "$MODDIR/autostart" ] || exit 0

# Manual experiments established this overlay works after boot.
# Only proceed to radio cycling if overlay fabrication + PM checks succeed.
/system/bin/sh "$MODDIR/apply.sh"
APPLY_RC=$?
echo "v0.4: apply exit=$APPLY_RC" >> "$LOG"
[ "$APPLY_RC" = 0 ] || exit 0
[ -f "$MODDIR/autostart" ] || exit 0

/system/bin/sh "$MODDIR/restart_bt.sh"
RESTART_RC=$?
echo "v0.4: Bluetooth restart exit=$RESTART_RC" >> "$LOG"
[ "$RESTART_RC" = 0 ] || exit "$RESTART_RC"
# Reconnect as soon as the adapter/profile are ready; no app/unlock dependency.
/system/bin/sh "$MODDIR/reconnect.sh"
exit 0
