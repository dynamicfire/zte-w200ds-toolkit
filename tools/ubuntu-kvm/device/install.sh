#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PREFIX=/data/data/com.termux/files/usr
TERMUX_HOME=/data/data/com.termux/files/home

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
    echo "--force 会在覆盖不同的现有脚本前创建带时间戳的备份。"
    exit 0
    ;;
  *) fail "未知参数：$1" ;;
esac
[ "$#" -le 1 ] || fail "参数过多。"

[ "$(id -u)" -ne 0 ] || fail "请在 Termux 普通用户下运行，不要使用 su。"
[ -d "$PREFIX/bin" ] && [ -d "$TERMUX_HOME" ] || fail "当前环境不是预期的 Termux 主应用。"
[ "$HOME" = "$TERMUX_HOME" ] || fail "HOME 与预期 Termux 主目录不一致。"
[ ! -L "$TERMUX_HOME/bin" ] || fail "拒绝使用符号链接目录：$TERMUX_HOME/bin"
if [ -e "$TERMUX_HOME/bin" ]; then
  [ -d "$TERMUX_HOME/bin" ] || fail "命令路径不是目录：$TERMUX_HOME/bin"
else
  install -d -m 700 "$TERMUX_HOME/bin"
fi

sources=(
  "$SCRIPT_DIR/u26.sh"
  "$SCRIPT_DIR/start-ubuntu26-root.sh"
  "$SCRIPT_DIR/status-ubuntu26-root.sh"
  "$SCRIPT_DIR/connect-ubuntu26.sh"
  "$SCRIPT_DIR/status-ubuntu26.sh"
  "$SCRIPT_DIR/stop-ubuntu26.sh"
)
targets=(
  "$PREFIX/bin/u26"
  "$TERMUX_HOME/bin/start-ubuntu26-root"
  "$TERMUX_HOME/bin/status-ubuntu26-root"
  "$TERMUX_HOME/bin/connect-ubuntu26"
  "$TERMUX_HOME/bin/status-ubuntu26"
  "$TERMUX_HOME/bin/stop-ubuntu26"
)

# 先完整预检，避免遇到冲突时只安装了一部分文件。
for index in "${!sources[@]}"; do
  source_file="${sources[$index]}"
  target_file="${targets[$index]}"
  [ -f "$source_file" ] || fail "缺少源码：$source_file"
  [ ! -L "$target_file" ] || fail "拒绝覆盖符号链接：$target_file"
  if [ -e "$target_file" ]; then
    [ -f "$target_file" ] || fail "目标不是普通文件：$target_file"
    if ! cmp -s "$source_file" "$target_file" && [ "$force" -ne 1 ]; then
      fail "已有不同的 ${target_file}；核对后使用 --force，安装器会先备份。"
    fi
  fi
done

timestamp="$(date '+%Y%m%d-%H%M%S')"
for index in "${!sources[@]}"; do
  source_file="${sources[$index]}"
  target_file="${targets[$index]}"
  same_content=0
  if [ -e "$target_file" ] && cmp -s "$source_file" "$target_file"; then
    same_content=1
  elif [ -e "$target_file" ]; then
    backup="$target_file.before-$timestamp"
    [ ! -e "$backup" ] || fail "备份目标已存在：$backup"
    cp -p "$target_file" "$backup"
    echo "已备份：$backup"
  fi

  if [ "$target_file" = "$PREFIX/bin/u26" ]; then
    target_mode=755
  else
    target_mode=700
  fi

  # 先在目标目录创建同 UID 的新 inode 再原子替换，确保旧文件的异常 mode/SELinux 标签不会遗留。
  temporary_target="$target_file.install.$$"
  [ ! -e "$temporary_target" ] || fail "临时目标已存在：$temporary_target"
  install -m "$target_mode" "$source_file" "$temporary_target"
  mv -f "$temporary_target" "$target_file"
  if [ "$same_content" -eq 1 ]; then
    echo "内容相同，已刷新权限和标签：$target_file"
  else
    echo "已安装：$target_file"
  fi
done

echo "安装完成。运行 u26 status 核对状态。"
