#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BIN_DIR=${U26_BIN_DIR:-"$HOME/.local/bin"}
DATA_DIR=${U26_DATA_DIR:-"$HOME/Library/Application Support/W200DS/ubuntu26"}
CONFIG_FILE="$DATA_DIR/config"
TARGET="$BIN_DIR/u26"

fail() {
  echo "$*" >&2
  exit 1
}

force=0
case "${1:-}" in
  '') ;;
  --force) force=1 ;;
  -h|--help)
    echo "用法：$0 [--force]"
    echo "--force 会在覆盖不同的现有 u26 前创建带时间戳的备份。"
    exit 0
    ;;
  *) fail "未知参数：$1" ;;
esac
[ "$#" -le 1 ] || fail "参数过多。"

if [ -L "$BIN_DIR" ]; then
  fail "拒绝使用符号链接目录：$BIN_DIR"
elif [ -e "$BIN_DIR" ]; then
  [ -d "$BIN_DIR" ] || fail "命令目录不是普通目录：$BIN_DIR"
else
  install -d -m 755 "$BIN_DIR"
fi

current_uid=$(id -u)
for private_dir in "$DATA_DIR" "$DATA_DIR/ssh"; do
  if [ -L "$private_dir" ]; then
    fail "拒绝使用符号链接目录：$private_dir"
  elif [ -e "$private_dir" ]; then
    [ -d "$private_dir" ] || fail "私有状态路径不是目录：$private_dir"
  else
    install -d -m 700 "$private_dir"
  fi

  private_uid=$(stat -f '%u' "$private_dir" 2>/dev/null || true)
  private_mode=$(stat -f '%Lp' "$private_dir" 2>/dev/null || true)
  [ "$private_uid" = "$current_uid" ] || fail "私有状态目录所有者不是当前用户：$private_dir"
  [ "$private_mode" = 700 ] || fail "私有状态目录权限必须是 0700：$private_dir"
done

# 在改动命令入口前先验证现有配置目标，避免发生部分安装。
if [ -L "$CONFIG_FILE" ]; then
  fail "拒绝使用符号链接配置：$CONFIG_FILE"
elif [ -e "$CONFIG_FILE" ] && [ ! -f "$CONFIG_FILE" ]; then
  fail "配置目标不是普通文件：$CONFIG_FILE"
fi

if [ -L "$TARGET" ]; then
  fail "拒绝覆盖符号链接：$TARGET"
elif [ -e "$TARGET" ]; then
  [ -f "$TARGET" ] || fail "目标不是普通文件：$TARGET"
  if cmp -s "$SCRIPT_DIR/u26" "$TARGET"; then
    echo "命令已是最新版本：$TARGET"
  elif [ "$force" -eq 1 ]; then
    backup="$TARGET.before-$(date '+%Y%m%d-%H%M%S')"
    [ ! -e "$backup" ] || fail "备份目标已存在：$backup"
    cp -p "$TARGET" "$backup"
    install -m 755 "$SCRIPT_DIR/u26" "$TARGET"
    echo "已备份旧命令：$backup"
  else
    fail "已有不同的 ${TARGET}；核对后使用 --force，安装器会先备份。"
  fi
else
  install -m 755 "$SCRIPT_DIR/u26" "$TARGET"
fi

if [ ! -e "$CONFIG_FILE" ]; then
  install -m 600 "$SCRIPT_DIR/config.example" "$CONFIG_FILE"
  echo "已创建配置模板：$CONFIG_FILE"
  echo "请填写 ADB 序列号，并把 SSH 私钥与 known_hosts 放入 $DATA_DIR/ssh/。"
else
  echo "保留已有配置：$CONFIG_FILE"
fi

echo "已安装命令：$TARGET"
