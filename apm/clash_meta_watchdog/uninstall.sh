#!/system/bin/sh

STATE_DIR=/data/adb/clash_meta_watchdog
PID_FILE="$STATE_DIR/watchdog.pid"

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

mkdir -p "$STATE_DIR" 2>/dev/null
: > "$STATE_DIR/stop"

watchdog_pid=
[ -r "$PID_FILE" ] && watchdog_pid=$(sed -n '1p' "$PID_FILE")
if is_our_watchdog "$watchdog_pid"; then
  kill "$watchdog_pid" 2>/dev/null
  waited=0
  while is_our_watchdog "$watchdog_pid" && [ "$waited" -lt 5 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if is_our_watchdog "$watchdog_pid"; then
    kill -9 "$watchdog_pid" 2>/dev/null
    sleep 1
  fi
fi

# Remove only this module's private runtime files. Clash Meta's app data and
# desired-state marker are deliberately left untouched.
rm -f "$STATE_DIR/watchdog.pid"
rm -f "$STATE_DIR/events.log"
rm -f "$STATE_DIR/events.log.tmp"
rm -f "$STATE_DIR/paused"
rm -f "$STATE_DIR/stop"
rmdir "$STATE_DIR/instance.lock" 2>/dev/null
rmdir "$STATE_DIR" 2>/dev/null
exit 0
