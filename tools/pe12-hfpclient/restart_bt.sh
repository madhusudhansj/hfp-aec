#!/system/bin/sh
# Pixel Experience 12 / star2lte: one verified Bluetooth OFF -> ON cycle.
# The device was tested with `svc bluetooth disable/enable`; `cmd bluetooth_manager`
# prints "No shell command implementation" on this ROM and must not be used.
MODDIR=${0%/*}
LOG="$MODDIR/hfp_boot.log"
log() { echo "$*" >> "$LOG"; }

get_bt_state() {
  /system/bin/dumpsys bluetooth_manager 2>/dev/null | \
    /system/bin/awk '/^[[:space:]]*state: / {print $2; exit}'
}

wait_bt_state() {
  EXPECTED="$1"
  LIMIT="$2"
  I=0
  while [ "$I" -lt "$LIMIT" ]; do
    BT_STATE=$(get_bt_state)
    case "$EXPECTED:$BT_STATE" in
      ON:ON|OFF:OFF|OFF:BLE_ON) return 0 ;;
    esac
    sleep 1
    I=$((I+1))
  done
  return 1
}

log "=== HFP v0.4 BT restart: $(date) ==="
if [ "$(id -u)" != 0 ]; then
  log 'ERROR: root required'
  exit 10
fi
if [ ! -x /system/bin/svc ]; then
  log 'ERROR: /system/bin/svc unavailable'
  exit 11
fi

EFFECTIVE=$(/system/bin/cmd overlay lookup com.android.bluetooth \
    com.android.bluetooth:bool/profile_supported_hfpclient)
if [ "$EFFECTIVE" != true ]; then
  log "SKIP: HFP resource not true ($EFFECTIVE); refusing to restart Bluetooth"
  exit 12
fi

# Wait for a running adapter; never turn it on if it was already switched off.
if ! wait_bt_state ON 30; then
  log "SKIP: Bluetooth not ON; state=$(get_bt_state) preference=$(/system/bin/settings get global bluetooth_on)"
  exit 0
fi
PREF=$(/system/bin/settings get global bluetooth_on)
if [ "$PREF" = 0 ]; then
  log 'SKIP: saved Bluetooth preference was OFF'
  exit 0
fi
log "Before restart: state=$(get_bt_state), preference=$PREF"

# Do not redirect stdout/stderr of Android service shell commands to $LOG.
/system/bin/svc bluetooth disable
DISABLE_RC=$?
log "svc disable rc=$DISABLE_RC, immediate state=$(get_bt_state)"
if [ "$DISABLE_RC" -ne 0 ]; then
  log 'ERROR: svc disable failed; no automatic retry'
  exit 20
fi

# BLE_ON means Classic Bluetooth is down (LE-only); either BLE_ON or OFF is
# sufficient for unloading Classic Bluetooth profile services.
if ! wait_bt_state OFF 40; then
  log "ERROR: Bluetooth never reached OFF/BLE_ON; state=$(get_bt_state). Restoring ON once."
  /system/bin/svc bluetooth enable
  RESTORE_RC=$?
  log "recovery svc enable rc=$RESTORE_RC"
  exit 21
fi
log "Classic Bluetooth stopped: state=$BT_STATE"
sleep 3

/system/bin/svc bluetooth enable
ENABLE_RC=$?
log "svc enable rc=$ENABLE_RC, immediate state=$(get_bt_state)"
if [ "$ENABLE_RC" -ne 0 ]; then
  log 'ERROR: svc enable failed. Manually enable Bluetooth in Settings.'
  exit 22
fi
if ! wait_bt_state ON 45; then
  log "ERROR: Bluetooth did not return to ON; state=$(get_bt_state)"
  exit 23
fi

# Profile registration can lag the adapter ON event.
I=0
while [ "$I" -lt 20 ]; do
  if /system/bin/dumpsys bluetooth_manager | /system/bin/grep -q 'Profile: HeadsetClientService'; then
    log 'SUCCESS: svc OFF/ON completed; HeadsetClientService is running'
    exit 0
  fi
  sleep 1
  I=$((I+1))
done
log 'WARN: Bluetooth ON, but HeadsetClientService missing after waiting'
exit 24
