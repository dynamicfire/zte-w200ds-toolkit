#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
SU=/system/bin/su

ROOT_START="$TERMUX_HOME/bin/start-ubuntu26-root"
ROOT_STATUS="$TERMUX_HOME/bin/status-ubuntu26-root"
USER_CONNECT="$TERMUX_HOME/bin/connect-ubuntu26"
USER_STATUS="$TERMUX_HOME/bin/status-ubuntu26"
USER_STOP="$TERMUX_HOME/bin/stop-ubuntu26"

SSH="$PREFIX/bin/ssh"
TIMEOUT="$PREFIX/bin/timeout"
KEY="$TERMUX_HOME/.ssh/w200ds_ubuntu26_ed25519"
KNOWN_HOSTS="$TERMUX_HOME/vm/ubuntu26/ssh/known_hosts"

require_root() {
  if [ ! -x "$SU" ]; then
    echo "Termux 当前看不到 APatch su；请先在 APatch 中给 Termux 授予超级用户权限。" >&2
    return 1
  fi

  # GNU timeout 默认会为子命令建立独立进程组。在交互式 Termux 中，
  # APatch su 因此可能被当作后台 TTY 读者暂停；--foreground 保留前台终端归属。
  root_uid="$($TIMEOUT --foreground 10 "$SU" -c 'id -u' 2>/dev/null || true)"
  if [ "$root_uid" != 0 ]; then
    echo "Termux 尚未获得可用的 APatch Root 权限。" >&2
    return 1
  fi
}

capture_root_status() {
  set +e
  ROOT_STATUS_OUTPUT="$($TIMEOUT --foreground 10 "$SU" -c "$ROOT_STATUS" 2>&1)"
  ROOT_STATUS_RC=$?
  set -e

  # APatch su 11224 在交互式 TTY 中可能把子命令的 10/20 退出码归一为 0。
  # 状态脚本本身采用 fail-closed 的 STATE= 前缀，因此同时核对返回码与明确状态文本。
  case "$ROOT_STATUS_RC" in
    0|10|20)
      case "$ROOT_STATUS_OUTPUT" in
        STATE=running*) ROOT_STATUS_STATE=running ;;
        STATE=stopped*) ROOT_STATUS_STATE=stopped ;;
        STATE=unknown*) ROOT_STATUS_STATE=unknown ;;
        *) ROOT_STATUS_STATE=error ;;
      esac
      ;;
    *) ROOT_STATUS_STATE=error ;;
  esac
}

wait_for_ssh() {
  for second in $(seq 1 60); do
    if "$SSH" \
      -i "$KEY" \
      -p 2222 \
      -o BatchMode=yes \
      -o IdentitiesOnly=yes \
      -o ConnectTimeout=2 \
      -o StrictHostKeyChecking=yes \
      -o UserKnownHostsFile="$KNOWN_HOSTS" \
      ubuntu@127.0.0.1 true >/dev/null 2>&1; then
      echo "Ubuntu 26.04 已就绪（${second}s）。"
      return 0
    fi
    sleep 1
  done

  echo "QEMU 已启动，但 60 秒内 SSH 未就绪；请运行：u26 status" >&2
  return 1
}

start_guest() {
  require_root
  "$SU" -c "$ROOT_START"
  wait_for_ssh
}

connect_guest() {
  exec "$USER_CONNECT"
}

show_status() {
  require_root
  capture_root_status
  printf '%s\n' "$ROOT_STATUS_OUTPUT"
  case "$ROOT_STATUS_STATE" in
    running)
      "$USER_STATUS"
      ;;
    stopped)
      return 0
      ;;
    unknown)
      echo "状态未知；请不要启动、强制停止或操作虚拟磁盘。" >&2
      return 1
      ;;
    *)
      echo "Root 状态检查失败，返回码=${ROOT_STATUS_RC}，且没有可信的 STATE 标记。" >&2
      return 1
      ;;
  esac
}

stop_guest() {
  require_root
  capture_root_status
  case "$ROOT_STATUS_STATE" in
    stopped)
      printf '%s\n' "$ROOT_STATUS_OUTPUT"
      echo "Ubuntu 已经处于关闭状态。"
      return 0
      ;;
    running) ;;
    unknown)
      printf '%s\n' "$ROOT_STATUS_OUTPUT" >&2
      echo "状态未知，拒绝发送关机命令。" >&2
      return 1
      ;;
    *)
      printf '%s\n' "$ROOT_STATUS_OUTPUT" >&2
      echo "Root 状态检查失败，拒绝发送关机命令。" >&2
      return 1
      ;;
  esac

  if "$USER_STOP"; then
    stop_request_rc=0
  else
    stop_request_rc=$?
    echo "SSH 关机请求返回码=${stop_request_rc}；仍将以 QEMU 是否完整退出作为最终结果。" >&2
  fi
  last_status="尚未完成第一次 Root 状态复核"
  for second in $(seq 1 30); do
    capture_root_status
    case "$ROOT_STATUS_STATE" in
      stopped)
        printf '%s\n' "$ROOT_STATUS_OUTPUT"
        echo "Ubuntu 已正常关机；QEMU 在 ${second}s 后确认退出。"
        return 0
        ;;
      running|unknown) last_status="$ROOT_STATUS_OUTPUT" ;;
      *) last_status="Root 状态检查失败（rc=${ROOT_STATUS_RC}）：$ROOT_STATUS_OUTPUT" ;;
    esac
    sleep 1
  done

  echo "30 秒内未能可靠确认 QEMU 退出，最终状态未知。" >&2
  printf '%s\n' "$last_status" >&2
  return 1
}

show_help() {
  cat <<'EOF'
Ubuntu 26.04 管理命令

  u26             打开中文菜单
  u26 go          启动并进入 Ubuntu
  u26 start       只启动 Ubuntu
  u26 connect     连接到已运行的 Ubuntu
  u26 status      查看状态
  u26 stop        正常关机

也支持中文参数：进入、启动、连接、状态、关机。
EOF
}

show_menu() {
  printf '%s\n' \
    "" \
    "Ubuntu Server 26.04" \
    "  1) 启动并进入" \
    "  2) 连接到已运行的 Ubuntu" \
    "  3) 查看状态" \
    "  4) 正常关机" \
    "  5) 退出" \
    ""
  read -r -p "请选择 [1-5]：" menu_choice
  case "$menu_choice" in
    1) start_guest; connect_guest ;;
    2) connect_guest ;;
    3) show_status ;;
    4) stop_guest ;;
    5) return 0 ;;
    *) echo "无效选择。" >&2; return 2 ;;
  esac
}

command_name="${1:-}"
case "$command_name" in
  '')
    if [ -t 0 ] && [ -t 1 ]; then
      show_menu
    else
      show_help
    fi
    ;;
  go|进入)
    start_guest
    connect_guest
    ;;
  start|启动)
    start_guest
    ;;
  connect|ssh|连接)
    connect_guest
    ;;
  status|状态)
    show_status
    ;;
  stop|关机)
    stop_guest
    ;;
  help|-h|--help|帮助)
    show_help
    ;;
  *)
    echo "未知参数：$command_name" >&2
    show_help >&2
    exit 2
    ;;
esac
