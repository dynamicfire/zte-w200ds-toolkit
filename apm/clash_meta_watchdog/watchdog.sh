#!/system/bin/sh

# This module intentionally reads only Clash Meta's own zero-byte desired-state
# marker. It never reads profiles, SharedPreferences, databases, logs, or URLs.

MODDIR=${1:-${0%/*}}
STATE_DIR=/data/adb/clash_meta_watchdog
PID_FILE="$STATE_DIR/watchdog.pid"
LOCK_DIR="$STATE_DIR/instance.lock"
LOG_FILE="$STATE_DIR/events.log"
STOP_FILE="$STATE_DIR/stop"
PAUSE_FILE="$STATE_DIR/paused"

USER_ID=0
PACKAGE=com.github.metacubex.clash.meta
BACKGROUND_PROCESS=com.github.metacubex.clash.meta:background
TUN_SERVICE=com.github.kr328.clash.service.TunService
TUN_COMPONENT=com.github.metacubex.clash.meta/com.github.kr328.clash.service.TunService
DESIRED_MARKER=/data/user/0/com.github.metacubex.clash.meta/files/service_running.lock
CONTROL_COMPONENT=com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity
START_ACTION=com.github.metacubex.clash.meta.action.START_CLASH

ACTIVE_INTERVAL=5
IDLE_INTERVAL=15
FULL_CHECK_EVERY=6
DEBOUNCE_SECONDS=3
START_TIMEOUT=30
STABLE_RESET_SECONDS=120
ATTEMPT_WINDOW_SECONDS=600
MAX_ATTEMPTS_PER_WINDOW=3
COOLDOWN_SECONDS=900

last_state=
attempts=0
window_started=0
stable_seconds=0
# Force an exact TunService/foreground validation on the first active pass.
full_check_tick=$FULL_CHECK_EVERY

rotate_log_if_needed() {
  [ -f "$LOG_FILE" ] || return 0
  bytes=$(wc -c < "$LOG_FILE" 2>/dev/null)
  case "$bytes" in
    ''|*[!0-9]*) return 0 ;;
  esac
  if [ "$bytes" -gt 65536 ]; then
    tail -n 200 "$LOG_FILE" > "$LOG_FILE.tmp" 2>/dev/null
    mv -f "$LOG_FILE.tmp" "$LOG_FILE"
    chmod 0600 "$LOG_FILE"
  fi
}

record_state() {
  state=$1
  [ "$state" = "$last_state" ] && return 0
  rotate_log_if_needed
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$state" >> "$LOG_FILE"
  chmod 0600 "$LOG_FILE"
  last_state=$state
}

cleanup() {
  current=
  [ -r "$PID_FILE" ] && current=$(sed -n '1p' "$PID_FILE")
  if [ "$current" = "$$" ]; then
    rm -f "$PID_FILE"
    rmdir "$LOCK_DIR" 2>/dev/null
  fi
}

trap 'cleanup; exit 0' HUP INT TERM
trap 'cleanup' EXIT

mkdir -p "$STATE_DIR" || exit 1
chmod 0700 "$STATE_DIR"
mkdir "$LOCK_DIR" 2>/dev/null || exit 0
printf '%s\n' "$$" > "$PID_FILE"
chmod 0600 "$PID_FILE"
record_state watchdog_started

package_installed() {
  /system/bin/pm path "$PACKAGE" >/dev/null 2>&1
}

desired_running() {
  [ -e "$DESIRED_MARKER" ]
}

package_explicitly_stopped() {
  user_line=$(/system/bin/dumpsys package "$PACKAGE" 2>/dev/null |
    sed -n '/User 0:/p' | sed -n '1p')
  case " $user_line " in
    *' stopped=true '*) return 0 ;;
    *) return 1 ;;
  esac
}

find_background_pid() {
  for candidate in $(/system/bin/pidof "$BACKGROUND_PROCESS" 2>/dev/null); do
    case "$candidate" in
      ''|*[!0-9]*) continue ;;
    esac
    [ -r "/proc/$candidate/cmdline" ] || continue
    process_name=$(tr '\000' '\n' < "/proc/$candidate/cmdline" 2>/dev/null |
      sed -n '1p')
    if [ "$process_name" = "$BACKGROUND_PROCESS" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

tun_service_present() {
  service_dump=$(/system/bin/dumpsys activity services "$TUN_COMPONENT" 2>/dev/null)
  case "$service_dump" in
    *"$TUN_SERVICE"*'isForeground=true'*) return 0 ;;
    *) return 1 ;;
  esac
}

healthy_fast() {
  background_pid=$(find_background_pid) || return 1
  [ -n "$background_pid" ]
}

healthy_full() {
  healthy_fast && tun_service_present
}

start_via_official_entrypoint() {
  timeout 10 /system/bin/am start --user "$USER_ID" \
    -a "$START_ACTION" \
    -n "$CONTROL_COMPONENT" \
    --activity-no-animation >/dev/null 2>&1
}

wait_for_recovery() {
  waited=0
  while [ "$waited" -lt "$START_TIMEOUT" ]; do
    [ -e "$STOP_FILE" ] && return 1
    [ -e "$PAUSE_FILE" ] && return 1
    desired_running || return 2

    if healthy_fast; then
      sleep 2
      if healthy_full; then
        # Do not declare success from the service's short loading phase. Give
        # the VPN runtime time to establish its interface, then check again.
        sleep 5
        waited=$((waited + 5))
        desired_running || return 2
        healthy_full && return 0
      fi
    fi

    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

rate_limit_allows_attempt() {
  now=$(date +%s)
  if [ "$window_started" -eq 0 ] ||
     [ $((now - window_started)) -ge "$ATTEMPT_WINDOW_SECONDS" ]; then
    window_started=$now
    attempts=0
  fi

  if [ "$attempts" -ge "$MAX_ATTEMPTS_PER_WINDOW" ]; then
    return 1
  fi

  attempts=$((attempts + 1))
  return 0
}

cool_down() {
  record_state restart_cooldown
  remaining=$COOLDOWN_SECONDS
  while [ "$remaining" -gt 0 ]; do
    [ -e "$STOP_FILE" ] && return 1
    [ -e "$PAUSE_FILE" ] && return 1
    desired_running || return 1
    sleep "$IDLE_INTERVAL"
    remaining=$((remaining - IDLE_INTERVAL))
  done
  window_started=$(date +%s)
  attempts=0
  return 0
}

while :; do
  [ -e "$STOP_FILE" ] && break

  if [ -e "$PAUSE_FILE" ]; then
    record_state paused
    stable_seconds=0
    attempts=0
    window_started=0
    sleep "$IDLE_INTERVAL"
    continue
  fi

  if ! package_installed; then
    record_state package_absent
    stable_seconds=0
    sleep 30
    continue
  fi

  if ! desired_running; then
    record_state vpn_not_requested
    stable_seconds=0
    attempts=0
    window_started=0
    sleep "$IDLE_INTERVAL"
    continue
  fi

  full_check_tick=$((full_check_tick + 1))
  if healthy_fast; then
    if [ "$full_check_tick" -lt "$FULL_CHECK_EVERY" ] || tun_service_present; then
      [ "$full_check_tick" -ge "$FULL_CHECK_EVERY" ] && full_check_tick=0
      record_state healthy
      stable_seconds=$((stable_seconds + ACTIVE_INTERVAL))
      if [ "$stable_seconds" -ge "$STABLE_RESET_SECONDS" ]; then
        attempts=0
        window_started=0
      fi
      sleep "$ACTIVE_INTERVAL"
      continue
    fi
  fi

  # Avoid alternating log entries while a Settings-level Force stop remains in
  # effect. Recheck after the debounce as well to close the stop/restart race.
  if package_explicitly_stopped; then
    record_state explicit_force_stop
    stable_seconds=0
    attempts=0
    window_started=0
    sleep "$IDLE_INTERVAL"
    continue
  fi

  record_state unexpected_service_loss
  stable_seconds=0
  sleep "$DEBOUNCE_SECONDS"

  [ -e "$STOP_FILE" ] && break
  [ -e "$PAUSE_FILE" ] && continue
  desired_running || continue
  healthy_full && continue

  # A Settings-level Force stop is treated as an explicit user choice. The ZTE
  # recents-lock workaround is a prerequisite: its SIGKILL path does not set
  # PackageManager's stopped flag, while Force stop does.
  if package_explicitly_stopped; then
    record_state explicit_force_stop
    attempts=0
    window_started=0
    sleep "$IDLE_INTERVAL"
    continue
  fi

  if ! rate_limit_allows_attempt; then
    cool_down
    continue
  fi

  case "$attempts" in
    2) sleep 10 ;;
    3) sleep 30 ;;
  esac

  desired_running || continue
  [ -e "$PAUSE_FILE" ] && continue

  record_state restart_attempt
  start_via_official_entrypoint
  wait_for_recovery
  recovery_result=$?
  case "$recovery_result" in
    0)
      record_state restart_succeeded
      full_check_tick=0
      ;;
    2)
      record_state vpn_stopped_by_user
      attempts=0
      window_started=0
      ;;
    *)
      record_state restart_failed
      ;;
  esac
done

record_state watchdog_stopped
exit 0
