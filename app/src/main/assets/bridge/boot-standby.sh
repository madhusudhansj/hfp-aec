#!/system/bin/sh
# Installed by the root bridge. Bounded boot recovery; no UI or idle audio.
package='@@PACKAGE@@'
attempt=0
while [ "$attempt" -lt 360 ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] &&
     [ "$(am get-started-user-state 0 2>/dev/null)" = RUNNING_UNLOCKED ]; then
    pm path "$package" >/dev/null 2>&1 || exit 0
    # A shell-sent boot broadcast lacks Android's real boot FGS exemption.
    # Grant only a short startup window, not a permanent battery exemption.
    cmd deviceidle tempwhitelist -d 20000 "$package" >/dev/null 2>&1
    # Receiver checks the saved master switch. Repeated starts are idempotent.
    am broadcast --user 0 -a android.intent.action.BOOT_COMPLETED \
      -n "$package/com.hfpengine.s9.BootReceiver" >/dev/null 2>&1
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 5
done
