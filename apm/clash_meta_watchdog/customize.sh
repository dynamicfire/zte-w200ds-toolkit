#!/system/bin/sh

ui_print "- Installing Clash Meta VPN Watchdog for ZTE W200DS"
ui_print "- No system partition overlay and no SELinux policy are used"
ui_print "- Action can start the watchdog now; the vendor whitelist still needs a reboot"

if [ "${API:-0}" -lt 26 ]; then
  abort "Android 8.0 or newer is required"
fi

set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/watchdog.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
