#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home
KEY="$TERMUX_HOME/.ssh/w200ds_ubuntu26_ed25519"
KNOWN_HOSTS="$TERMUX_HOME/vm/ubuntu26/ssh/known_hosts"

exec "$PREFIX/bin/ssh" \
  -i "$KEY" \
  -p 2222 \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$KNOWN_HOSTS" \
  -o ServerAliveInterval=30 \
  ubuntu@127.0.0.1
