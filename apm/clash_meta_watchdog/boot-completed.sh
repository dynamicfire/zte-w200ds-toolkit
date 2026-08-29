#!/system/bin/sh

MODDIR=${0%/*}
STATE_DIR=/data/adb/clash_meta_watchdog
PID_FILE="$STATE_DIR/watchdog.pid"
LOCK_DIR="$STATE_DIR/instance.lock"
APATCH_BUSYBOX=/data/adb/ap/bin/busybox

is_our_watchdog() {
  candidate=$1
  case "$candidate" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ -r "/proc/$candidate/cmdline" ] || return 1
  command_line=$(tr '\000' ' ' < "/proc/$candidate/cmdline" 2>/dev/null)
  case "$command_line" in
    *'/clash_meta_watchdog/watchdog.sh'*) return 0 ;;
    *) return 1 ;;
  esac
}

mkdir -p "$STATE_DIR" || exit 1
chmod 0700 "$STATE_DIR"
rm -f "$STATE_DIR/stop"

old_pid=
[ -r "$PID_FILE" ] && old_pid=$(sed -n '1p' "$PID_FILE")
if is_our_watchdog "$old_pid"; then
  exit 0
fi

rm -f "$PID_FILE"
rmdir "$LOCK_DIR" 2>/dev/null
[ -x "$APATCH_BUSYBOX" ] || exit 1
"$APATCH_BUSYBOX" sh -o standalone \
  "$MODDIR/watchdog.sh" "$MODDIR" </dev/null >/dev/null 2>&1 &
exit 0
