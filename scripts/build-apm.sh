#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${ROOT_DIR}/apm/zte_w200ds_tablet_boot"
OUTPUT_DIR="${ROOT_DIR}/build/outputs"

command -v zip >/dev/null 2>&1 || {
    printf '错误：缺少 zip 命令。\n' >&2
    exit 1
}
command -v unzip >/dev/null 2>&1 || {
    printf '错误：缺少 unzip 命令。\n' >&2
    exit 1
}

VERSION="$(sed -n 's/^version=//p' "${MODULE_DIR}/module.prop")"
[[ -n "${VERSION}" ]] || {
    printf '错误：module.prop 中没有 version。\n' >&2
    exit 1
}

FILES=(module.prop system.prop skip_mount README.md)
for file in "${FILES[@]}"; do
    [[ -f "${MODULE_DIR}/${file}" ]] || {
        printf '错误：模块缺少 %s。\n' "${file}" >&2
        exit 1
    }
done

mkdir -p -- "${OUTPUT_DIR}"
ZIP_BASENAME="zte-w200ds-tablet-default-apm-v${VERSION}.zip"
ZIP_PATH="${OUTPUT_DIR}/${ZIP_BASENAME}"
rm -f -- "${ZIP_PATH}" "${ZIP_PATH}.sha256"

(
    cd -- "${MODULE_DIR}"
    zip -q -X "${ZIP_PATH}" "${FILES[@]}"
)
unzip -tq "${ZIP_PATH}"

if command -v sha256sum >/dev/null 2>&1; then
    (
        cd -- "${OUTPUT_DIR}"
        sha256sum "${ZIP_BASENAME}" > "${ZIP_BASENAME}.sha256"
    )
elif command -v shasum >/dev/null 2>&1; then
    (
        cd -- "${OUTPUT_DIR}"
        shasum -a 256 "${ZIP_BASENAME}" > "${ZIP_BASENAME}.sha256"
    )
fi

printf '构建完成：%s\n' "${ZIP_PATH}"
