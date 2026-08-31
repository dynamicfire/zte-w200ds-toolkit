#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
KEY="$TERMUX_HOME/.ssh/w200ds_ubuntu26_ed25519"
VM_ROOT="$TERMUX_HOME/vm/ubuntu26"
KNOWN_HOSTS="$VM_ROOT/ssh/known_hosts"
SSH="$PREFIX/bin/ssh"

if "$SSH" \
  -i "$KEY" \
  -p 2222 \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$KNOWN_HOSTS" \
  ubuntu@127.0.0.1 \
  'sudo -n systemctl --no-block poweroff'; then
  echo "已向 Ubuntu 发送正常关机命令；u26 将继续确认 QEMU 是否退出。"
else
  request_rc=$?
  echo "SSH 关机请求未正常返回（rc=${request_rc}）。" >&2
  exit "$request_rc"
fi
