#!/bin/sh
set -eu

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TOOL_ROOT=$(CDPATH= cd -- "$TEST_DIR/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$TOOL_ROOT/../.." && pwd)

sh -n "$TOOL_ROOT/macos/u26" "$TOOL_ROOT/macos/install.sh"

for script in "$TOOL_ROOT"/device/*.sh; do
  bash -n "$script"
done

SENSITIVE_PATTERN='-----BEGIN ([A-Z0-9]+[[:space:]]+)*PRIVATE KEY-----|/Users/[^/[:space:]]+/|[0-9]{18,}|u0_a[0-9]{3,}|(ssh-(ed25519|rsa|dss)|ecdsa-sha2-nistp(256|384|521)|sk-(ssh-ed25519|ecdsa-sha2-nistp256)@openssh.com)[[:space:]]+[A-Za-z0-9+/]{20,}'
set +e
grep -R -n -E -e "$SENSITIVE_PATTERN" \
  "$TOOL_ROOT/device" \
  "$TOOL_ROOT/macos" \
  "$TOOL_ROOT/cloud-init" \
  "$TOOL_ROOT/README.md" \
  "$REPO_ROOT/README.md" \
  "$REPO_ROOT/CHANGELOG.md" \
  "$REPO_ROOT/docs/UBUNTU-KVM.md" \
  "$REPO_ROOT/docs/TESTING.md"
sensitive_rc=$?
set -e
case "$sensitive_rc" in
  0)
    echo "发现私钥、真实本机路径、设备序列号或实际 SSH 公钥。" >&2
    exit 1
    ;;
  1) ;;
  *)
    echo "敏感内容扫描执行失败（rc=${sensitive_rc}）。" >&2
    exit 1
    ;;
esac

if find "$TOOL_ROOT" -type f \( \
  -name '*.qcow2' -o -name '*.img' -o -name '*.iso' -o -name '*.fd' \
  -o -name '*.raw' -o -name '*.snap' -o -name '*.sock' -o -name '*.pid' \
  -o -name '*.log' -o -name '*.lock' -o -name '*.before-*' \
  -o -name 'id_*' -o -name '*_ed25519*' -o -name 'known_hosts*' \
  -o -name 'authorized_keys' -o -name 'config' \
  -o -name 'user-data' -o -name 'meta-data' -o -name 'network-config' \
\) | grep -q .; then
  echo "工具目录中出现了禁止提交的 VM、运行态或 SSH 身份文件。" >&2
  exit 1
fi

grep -q "replace-with-adb-serial" "$TOOL_ROOT/macos/config.example"
grep -q "REPLACE_WITH_MAC_PUBLIC_KEY" "$TOOL_ROOT/cloud-init/user-data.example"
grep -q "REPLACE_WITH_TERMUX_PUBLIC_KEY" "$TOOL_ROOT/cloud-init/user-data.example"

for private_path in \
  tools/ubuntu-kvm/vm/run/start.lock \
  tools/ubuntu-kvm/cloud-init/user-data \
  tools/ubuntu-kvm/cloud-init/meta-data \
  tools/ubuntu-kvm/cloud-init/network-config \
  tools/ubuntu-kvm/macos/config \
  tools/ubuntu-kvm/ssh/authorized_keys \
  tools/ubuntu-kvm/ssh/id_rsa; do
  git -C "$REPO_ROOT" check-ignore -q "$private_path" || {
    echo ".gitignore 未覆盖：$private_path" >&2
    exit 1
  }
done

echo "Ubuntu/KVM 工具静态检查通过。"
