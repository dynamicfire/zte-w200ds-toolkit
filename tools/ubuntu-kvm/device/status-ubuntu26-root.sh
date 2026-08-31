#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "此脚本必须由 Root 执行。" >&2
  exit 1
fi

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
QEMU="$PREFIX/bin/qemu-system-aarch64"
VM_ROOT="$TERMUX_HOME/vm/ubuntu26"
PID_FILE="$VM_ROOT/run/qemu.pid"
QMP_SOCKET="$VM_ROOT/run/qmp.sock"
DISK="$VM_ROOT/disk/ubuntu26.qcow2"
THREAD_MASK=f0
STOPPED_EXIT=10
UNKNOWN_EXIT=20

vm_pid_is_live() {
  local candidate cmdline
  candidate="${1:-}"
  case "$candidate" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "$candidate" -gt 1 ] || return 1
  [ "$(readlink "/proc/$candidate/exe" 2>/dev/null || true)" = "$QEMU" ] || return 1
  cmdline="$(tr '\0' ' ' < "/proc/$candidate/cmdline" 2>/dev/null || true)"
  case "$cmdline" in
    *" -name ubuntu26-server "*"$DISK"*) return 0 ;;
    *) return 1 ;;
  esac
}

find_vm_pids() {
  local process_list candidate_list candidate
  process_list="$("$PREFIX/bin/ps" -A -o pid=,comm=)" || return 1
  # 仅以进程名作宽预筛，以兼容 TASK_COMM_LEN 截断；下面仍做三项强身份核对。
  candidate_list="$(printf '%s\n' "$process_list" | awk '$2 ~ /qemu-system/ { print $1 }')"
  for candidate in $candidate_list; do
    if vm_pid_is_live "$candidate"; then
      printf '%s\n' "$candidate"
    fi
  done
}

vm_pid_list="$(find_vm_pids)" || {
  echo "STATE=unknown：无法枚举 QEMU 进程。" >&2
  exit "$UNKNOWN_EXIT"
}
set -- $vm_pid_list
if [ "$#" -eq 0 ]; then
  if "$PREFIX/bin/ss" -ltn 2>/dev/null | grep -Eq '127\.0\.0\.1:2222[[:space:]]'; then
    echo "STATE=unknown：未发现本实例 QEMU，但 127.0.0.1:2222 仍被占用。" >&2
    exit "$UNKNOWN_EXIT"
  fi
  echo "STATE=stopped：未发现匹配可执行文件、实例名和磁盘路径的 QEMU，SSH 转发端口也未监听。"
  exit "$STOPPED_EXIT"
fi

if [ "$#" -gt 1 ]; then
  echo "STATE=unknown：发现多个匹配本实例的 QEMU 进程：$*" >&2
  exit "$UNKNOWN_EXIT"
fi

pid="$1"
if [ ! -S "$QMP_SOCKET" ]; then
  echo "STATE=unknown：QEMU PID=$pid 存活，但 QMP socket 缺失。" >&2
  exit "$UNKNOWN_EXIT"
fi

qmp_permissions="$("$PREFIX/bin/stat" -c '%u:%g:%a' "$QMP_SOCKET" 2>/dev/null || true)"
if [ "$qmp_permissions" != "0:0:600" ]; then
  echo "STATE=unknown：QMP socket 权限为 ${qmp_permissions}，预期 0:0:600。" >&2
  exit "$UNKNOWN_EXIT"
fi

if ! "$PREFIX/bin/ss" -ltn 2>/dev/null | grep -Eq '127\.0\.0\.1:2222[[:space:]]'; then
  echo "STATE=unknown：QEMU PID=$pid 存活，但 SSH 转发端口未监听。" >&2
  exit "$UNKNOWN_EXIT"
fi

affinity_output="$(taskset -ap "$pid" 2>/dev/null)" || {
  echo "STATE=unknown：无法读取 QEMU PID=$pid 的线程亲和性。" >&2
  exit "$UNKNOWN_EXIT"
}

if ! printf '%s\n' "$affinity_output" | awk -F': ' -v expected="$THREAD_MASK" \
  'NF && $NF != expected { bad=1 } END { exit bad }'; then
  printf '%s\n' "$affinity_output" >&2
  echo "STATE=unknown：并非全部 QEMU 线程都绑定到掩码 ${THREAD_MASK}。" >&2
  exit "$UNKNOWN_EXIT"
fi

echo "STATE=running：QEMU/KVM 进程正常，PID=$pid"
printf '%s\n' "$affinity_output"
if [ -s "$PID_FILE" ] && [ "$(cat "$PID_FILE" 2>/dev/null || true)" = "$pid" ]; then
  echo "PID 文件与进程扫描一致。"
else
  echo "警告：PID 文件缺失或不一致；本次状态以完整进程扫描为准。" >&2
fi
echo "QMP socket 已核对为 root:root 0600；SSH 转发端口 127.0.0.1:2222 正在监听。"
