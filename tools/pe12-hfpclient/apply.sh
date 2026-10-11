#!/system/bin/sh
# Pixel Experience 12 / star2lte. Avoid redirecting Binder command stdout.
MODDIR=${0%/*}
LOG="$MODDIR/hfp_boot.log"
log() { echo "$*" >> "$LOG"; }
log "=== HFP v0.4 apply: $(date) ==="
log "Android: $(getprop ro.build.version.release); device: $(getprop ro.product.device)"

if [ "$(getprop ro.product.device)" != star2lte ] || \
   [ "$(getprop ro.build.version.release)" != 12 ]; then
  log 'ERROR: wrong ROM/device'
  exit 10
fi
if [ "$(id -u)" != 0 ]; then
  log 'ERROR: root required'
  exit 11
fi

# Reapplying after a manual test should be harmless.
VALUE=$(/system/bin/cmd overlay lookup com.android.bluetooth \
  com.android.bluetooth:bool/profile_supported_hfpclient)
if [ "$VALUE" != true ]; then
  /system/bin/cmd overlay fabricate \
    --target com.android.bluetooth \
    --name HfpBoot \
    com.android.bluetooth:bool/profile_supported_hfpclient 0x12 0x1
  FAB_RC=$?
  log "fabricate rc=$FAB_RC"
  if [ "$FAB_RC" -ne 0 ]; then
    # If an HfpBoot overlay already exists but is disabled, reuse it.
    log 'WARN: fabricate returned nonzero; will attempt to enable existing overlay'
  fi
  /system/bin/cmd overlay enable --user 0 com.android.shell:HfpBoot
  ENABLE_RC=$?
  log "overlay enable rc=$ENABLE_RC"
  if [ "$ENABLE_RC" -ne 0 ]; then
    log 'ERROR: could not enable overlay'
    exit 21
  fi
fi

VALUE=$(/system/bin/cmd overlay lookup com.android.bluetooth \
  com.android.bluetooth:bool/profile_supported_hfpclient)
log "effective resource: $VALUE"
if [ "$VALUE" != true ]; then
  log 'ERROR: resource not true; no component change or radio restart'
  exit 22
fi

/system/bin/pm enable --user 0 com.android.bluetooth/.hfpclient.HeadsetClientService
PM_RC=$?
log "pm enable rc=$PM_RC"
if [ "$PM_RC" -ne 0 ]; then
  log 'ERROR: pm enable failed'
  exit 23
fi
log 'OK: HFP overlay true and HeadsetClientService component enabled'
exit 0
