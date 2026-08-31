#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
VM_ROOT="$TERMUX_HOME/vm/ubuntu26"
KEY="$TERMUX_HOME/.ssh/w200ds_ubuntu26_ed25519"
KNOWN_HOSTS="$VM_ROOT/ssh/known_hosts"
SSH="$PREFIX/bin/ssh"

if "$SSH" \
  -i "$KEY" \
  -p 2222 \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o ConnectTimeout=5 \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$KNOWN_HOSTS" \
  ubuntu@127.0.0.1 \
  'set -eu
   ubuntu_state=$(systemctl is-system-running)
   ssh_state=$(systemctl is-active ssh)
   virt_type=$(systemd-detect-virt)
   disk_bytes=$(lsblk -bdn -o SIZE /dev/vda | tr -d "[:space:]")
   root_status=$(findmnt -no SIZE,AVAIL,USE% /)
   test "$ubuntu_state" = running
   test "$ssh_state" = active
   test "$virt_type" = kvm
   test "$disk_bytes" = 68719476736
   printf "Ubuntu：%s\nSSH：%s\n虚拟化：%s\n虚拟盘：%s bytes\n根分区：%s\n" \
     "$ubuntu_state" "$ssh_state" "$virt_type" "$disk_bytes" "$root_status"'; then
  exit 0
else
  echo "Ubuntu SSH 不可连接：虚拟机可能未运行、仍在启动，或主机密钥不匹配。" >&2
  exit 1
fi
