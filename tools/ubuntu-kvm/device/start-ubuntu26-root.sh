#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "此脚本必须由 Root 启动；请在 Termux 中运行：u26 start" >&2
  exit 1
fi

umask 077

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
TMPDIR="$PREFIX/tmp"
export PREFIX TMPDIR
export PATH="$PREFIX/bin:/system/bin"

VM_ROOT="$TERMUX_HOME/vm/ubuntu26"
QEMU="$PREFIX/bin/qemu-system-aarch64"
CODE="$VM_ROOT/firmware/edk2-code.fd"
VARS="$VM_ROOT/firmware/edk2-vars.fd"
DISK="$VM_ROOT/disk/ubuntu26.qcow2"
SEED="$VM_ROOT/seed/seed.iso"
RUN_DIR="$VM_ROOT/run"
PID_FILE="$RUN_DIR/qemu.pid"
QMP_SOCKET="$RUN_DIR/qmp.sock"
SERIAL_LOG="$VM_ROOT/log/serial.log"
SSH_PORT=2222
THREAD_MASK=f0

TERMUX_UID="$(stat -c %u "$TERMUX_HOME")"
TERMUX_GID="$(stat -c %g "$TERMUX_HOME")"

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
  # procps/toybox 可能提供完整 argv0，也可能受 TASK_COMM_LEN 截断；这里只做宽预筛。
  # 最终身份仍由 /proc/PID/exe、实例名和绝对磁盘路径共同确认。
  candidate_list="$(printf '%s\n' "$process_list" | awk '$2 ~ /qemu-system/ { print $1 }')"
  for candidate in $candidate_list; do
    if vm_pid_is_live "$candidate"; then
      printf '%s\n' "$candidate"
    fi
  done
}

all_threads_pinned() {
  local candidate affinity_output
  candidate="${1:-}"
  affinity_output="$(taskset -ap "$candidate" 2>/dev/null)" || return 1
  [ -n "$affinity_output" ] || return 1
  printf '%s\n' "$affinity_output" | awk -F': ' -v expected="$THREAD_MASK" \
    'NF && $NF != expected { bad=1 } END { exit bad }'
}

write_pid_file() {
  local candidate recovered_pid
  candidate="$1"
  recovered_pid="$PID_FILE.recovered.$$"
  printf '%s\n' "$candidate" > "$recovered_pid"
  chown "$TERMUX_UID:$TERMUX_GID" "$recovered_pid"
  chmod 600 "$recovered_pid"
  restorecon -F "$recovered_pid" >/dev/null
  mv -f "$recovered_pid" "$PID_FILE"
}

for required in "$QEMU" "$CODE" "$VARS" "$DISK" "$SEED"; do
  if [ ! -e "$required" ]; then
    echo "缺少必需文件：$required" >&2
    exit 1
  fi
done

if [ ! -c /dev/kvm ] || [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "Root 当前不能读写 /dev/kvm，停止启动。" >&2
  exit 1
fi

mkdir -p "$RUN_DIR" "$VM_ROOT/log"
chown "$TERMUX_UID:$TERMUX_GID" "$VM_ROOT" "$VM_ROOT/base" "$VM_ROOT/disk" \
  "$VM_ROOT/firmware" "$VM_ROOT/seed" "$VM_ROOT/log" "$RUN_DIR"
chmod 700 "$VM_ROOT" "$VM_ROOT/base" "$VM_ROOT/disk" "$VM_ROOT/firmware" \
  "$VM_ROOT/seed" "$VM_ROOT/log" "$RUN_DIR"

exec 9>"$RUN_DIR/start.lock"
if ! flock -n 9; then
  echo "另一个 Ubuntu 26.04 启动/检查流程正在运行，停止重复启动。" >&2
  exit 1
fi
trap 'flock -u 9' EXIT

vm_pid_list="$(find_vm_pids)" || {
  echo "无法枚举 QEMU 进程，状态未知，拒绝继续。" >&2
  exit 1
}
set -- $vm_pid_list
if [ "$#" -gt 1 ]; then
  echo "发现多个匹配本实例的 QEMU 进程：$*；状态未知，拒绝继续。" >&2
  exit 1
fi

if [ "$#" -eq 1 ]; then
  old_pid="$1"
  if [ ! -S "$QMP_SOCKET" ]; then
    echo "本实例 QEMU PID=$old_pid 仍在运行，但 QMP socket 缺失；拒绝启动第二个实例。" >&2
    exit 1
  fi
  chown 0:0 "$QMP_SOCKET"
  chmod 600 "$QMP_SOCKET"
  if ! "$PREFIX/bin/ss" -ltn 2>/dev/null | grep -Eq "127\\.0\\.0\\.1:${SSH_PORT}[[:space:]]"; then
    echo "本实例 QEMU PID=$old_pid 仍在运行，但 SSH 转发端口缺失；拒绝启动第二个实例。" >&2
    exit 1
  fi
  if ! all_threads_pinned "$old_pid"; then
    echo "本实例 QEMU PID=$old_pid 的线程并非全部绑定到掩码 ${THREAD_MASK}；拒绝把它报告为健康。" >&2
    exit 1
  fi
  write_pid_file "$old_pid"
  echo "Ubuntu 26.04 已在运行，PID=${old_pid}，全部 QEMU 线程亲和性=${THREAD_MASK}"
  exit 0
fi

# 只有完整进程扫描确认本实例不存在后，才清理陈旧运行文件。
rm -f "$PID_FILE" "$QMP_SOCKET"

if "$PREFIX/bin/ss" -ltn 2>/dev/null | grep -Eq "127\\.0\\.0\\.1:${SSH_PORT}[[:space:]]"; then
  echo "127.0.0.1:${SSH_PORT} 已被其他进程占用，停止启动。" >&2
  exit 1
fi

# T760 的 cpu0-3 是 Cortex-A55、cpu4-7 是 Cortex-A76。
# KVM 的 host CPU 模型不能让 vCPU 在线程启动时跨两种核心，因此固定到同构大核组。
taskset "$THREAD_MASK" "$QEMU" \
  -name ubuntu26-server \
  -machine virt,accel=kvm,gic-version=3 \
  -cpu host,sve=off \
  -smp cpus=2,sockets=1,cores=2,threads=1 \
  -m 1536 \
  -drive if=pflash,format=raw,unit=0,readonly=on,file="$CODE" \
  -drive if=pflash,format=raw,unit=1,file="$VARS" \
  -drive if=none,id=osdisk,format=qcow2,cache=writeback,discard=unmap,file="$DISK" \
  -device virtio-blk-pci,drive=osdisk \
  -drive if=none,id=seed,format=raw,readonly=on,file="$SEED" \
  -device virtio-blk-pci,drive=seed \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:${SSH_PORT}-:22 \
  -device virtio-net-pci,netdev=net0 \
  -object rng-random,id=rng0,filename=/dev/urandom \
  -device virtio-rng-pci,rng=rng0 \
  -display none \
  -chardev file,id=serial0,path="$SERIAL_LOG",append=on \
  -serial chardev:serial0 \
  -qmp unix:"$QMP_SOCKET",server=on,wait=off \
  -daemonize \
  -pidfile "$PID_FILE"

new_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
if ! vm_pid_is_live "$new_pid" || [ ! -S "$QMP_SOCKET" ] || ! all_threads_pinned "$new_pid"; then
  echo "QEMU 未通过启动后进程/QMP 检查，请查看串口日志。" >&2
  exit 1
fi

chown "$TERMUX_UID:$TERMUX_GID" "$PID_FILE" "$SERIAL_LOG"
chmod 600 "$PID_FILE" "$SERIAL_LOG"
restorecon -F "$PID_FILE" "$SERIAL_LOG" >/dev/null
chown 0:0 "$QMP_SOCKET"
chmod 600 "$QMP_SOCKET"

echo "QEMU/KVM 已拉起，PID=${new_pid}，全部 QEMU 线程亲和性=${THREAD_MASK}；Ubuntu 与 SSH 仍需就绪检查。"
echo "平板内连接：$TERMUX_HOME/bin/connect-ubuntu26"
echo "串口日志：$SERIAL_LOG"
