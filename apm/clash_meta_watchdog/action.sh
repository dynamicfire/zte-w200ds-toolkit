#!/system/bin/sh

STATE_DIR=/data/adb/clash_meta_watchdog
PAUSE_FILE="$STATE_DIR/paused"
PID_FILE="$STATE_DIR/watchdog.pid"
MODDIR=${0%/*}
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

watchdog_pid=
[ -r "$PID_FILE" ] && watchdog_pid=$(sed -n '1p' "$PID_FILE")

if ! is_our_watchdog "$watchdog_pid"; then
  rm -f "$PAUSE_FILE" "$STATE_DIR/stop" "$PID_FILE"
  if [ ! -x "$APATCH_BUSYBOX" ]; then
    echo "APatch BusyBox was not found; the watchdog was not started."
    exit 1
  fi
  "$APATCH_BUSYBOX" sh -o standalone "$MODDIR/boot-completed.sh"
  sleep 1
  watchdog_pid=
  [ -r "$PID_FILE" ] && watchdog_pid=$(sed -n '1p' "$PID_FILE")
  if is_our_watchdog "$watchdog_pid"; then
    echo "Clash Meta watchdog started without a reboot."
    exit 0
  fi
  echo "Clash Meta watchdog did not start; reboot once and inspect its private event log."
  exit 1
fi

if [ -e "$PAUSE_FILE" ]; then
  rm -f "$PAUSE_FILE"
  echo "Clash Meta watchdog resumed. It will only act while Clash's own running marker exists."
else
  : > "$PAUSE_FILE"
  chmod 0600 "$PAUSE_FILE"
  echo "Clash Meta watchdog paused. The currently running VPN was not stopped."
fi
