#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${ROOT_DIR}/xposed/zte_installer_fix"
BUILD_DIR="${ROOT_DIR}/build/installer-fix"
STUB_CLASSES_DIR="${BUILD_DIR}/stub-classes"
MODULE_CLASSES_DIR="${BUILD_DIR}/module-classes"
DEX_DIR="${BUILD_DIR}/dex"
OUTPUT_DIR="${ROOT_DIR}/build/outputs"
STUB_JAR="${BUILD_DIR}/xposed-api-stubs.jar"
MANIFEST="${MODULE_DIR}/AndroidManifest.xml"

fail() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "缺少命令 $1。请先阅读 xposed/zte_installer_fix/README.md。"
}

find_sdk_root() {
    local candidate
    for candidate in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME}/Android/Sdk" \
        "${HOME}/Library/Android/sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"; do
        if [[ -n "${candidate}" && -d "${candidate}" ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done
    return 1
}

find_latest_android_jar() {
    local platforms_dir="$1"
    local candidate
    while IFS= read -r candidate; do
        if [[ -f "${candidate}/android.jar" ]]; then
            printf '%s\n' "${candidate}/android.jar"
            return 0
        fi
    done < <(
        find "${platforms_dir}" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null |
            sort -Vr
    )
    return 1
}

write_checksum() {
    if command -v sha256sum >/dev/null 2>&1; then
        (
            cd -- "${OUTPUT_DIR}"
            sha256sum "${APK_BASENAME}" > "${APK_BASENAME}.sha256"
        )
    elif command -v shasum >/dev/null 2>&1; then
        (
            cd -- "${OUTPUT_DIR}"
            shasum -a 256 "${APK_BASENAME}" > "${APK_BASENAME}.sha256"
        )
    else
        printf '提示：未找到 sha256sum 或 shasum，未生成校验文件。\n' >&2
    fi
}

if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

for command_name in javac jar keytool sed zip; do
    require_command "${command_name}"
done

APP_VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "${MANIFEST}")"
APP_VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "${MANIFEST}")"
[[ "${APP_VERSION_CODE}" =~ ^[0-9]+$ ]] ||
    fail "无法从 Installer Fix Manifest 读取合法的 versionCode。"
[[ -n "${APP_VERSION_NAME}" ]] ||
    fail "无法从 Installer Fix Manifest 读取 versionName。"

APK_BASENAME="zte-installer-fix-v${APP_VERSION_NAME}.apk"
APK="${OUTPUT_DIR}/${APK_BASENAME}"

SDK_ROOT="$(find_sdk_root)" ||
    fail "没有找到 Android SDK。请设置 ANDROID_SDK_ROOT，或按 README 安装 SDK。"
BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
BUILD_TOOLS_DIR="${SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}"
[[ -d "${BUILD_TOOLS_DIR}" ]] ||
    fail "缺少 Android build-tools ${BUILD_TOOLS_VERSION}：${BUILD_TOOLS_DIR}"
ANDROID_JAR="$(find_latest_android_jar "${SDK_ROOT}/platforms")" ||
    fail "${SDK_ROOT}/platforms 中没有 android.jar。"

for tool in aapt2 d8 zipalign apksigner; do
    [[ -x "${BUILD_TOOLS_DIR}/${tool}" ]] ||
        fail "${BUILD_TOOLS_DIR} 中缺少可执行文件 ${tool}。"
done

rm -rf -- "${BUILD_DIR}"
mkdir -p -- \
    "${STUB_CLASSES_DIR}" \
    "${MODULE_CLASSES_DIR}" \
    "${DEX_DIR}" \
    "${OUTPUT_DIR}" \
    "${ROOT_DIR}/signing"
rm -f -- "${APK}" "${APK}.sha256"

DEFAULT_KEYSTORE="${ROOT_DIR}/signing/zte-installer-fix-debug.keystore"
KEYSTORE="${SIGNING_KEYSTORE:-${DEFAULT_KEYSTORE}}"
KEY_ALIAS="${SIGNING_ALIAS:-androiddebugkey}"
STORE_PASSWORD="${SIGNING_STOREPASS:-android}"
KEY_PASSWORD="${SIGNING_KEYPASS:-${STORE_PASSWORD}}"

if [[ "${KEYSTORE}" == "${DEFAULT_KEYSTORE}" && ! -f "${KEYSTORE}" ]]; then
    previous_umask="$(umask)"
    umask 077
    keytool -genkeypair \
        -keystore "${KEYSTORE}" \
        -storepass android \
        -keypass android \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
    umask "${previous_umask}"
elif [[ ! -f "${KEYSTORE}" ]]; then
    fail "指定的签名密钥不存在：${KEYSTORE}"
fi
if [[ "${KEYSTORE}" == "${DEFAULT_KEYSTORE}" ]]; then
    chmod 600 -- "${KEYSTORE}"
fi

mapfile -d '' STUB_SOURCES < <(
    find "${MODULE_DIR}/stubs" -type f -name '*.java' -print0 | sort -z
)
[[ "${#STUB_SOURCES[@]}" -gt 0 ]] || fail "stubs 目录中没有 Java 源码。"

javac \
    -encoding UTF-8 \
    --release 8 \
    -cp "${ANDROID_JAR}" \
    -d "${STUB_CLASSES_DIR}" \
    "${STUB_SOURCES[@]}"

jar cf "${STUB_JAR}" -C "${STUB_CLASSES_DIR}" .

mapfile -d '' MODULE_SOURCES < <(
    find "${MODULE_DIR}/src" -type f -name '*.java' -print0 | sort -z
)
[[ "${#MODULE_SOURCES[@]}" -gt 0 ]] || fail "src 目录中没有 Java 源码。"

javac \
    -encoding UTF-8 \
    --release 8 \
    -cp "${ANDROID_JAR}:${STUB_JAR}" \
    -d "${MODULE_CLASSES_DIR}" \
    "${MODULE_SOURCES[@]}"

mapfile -d '' MODULE_CLASS_FILES < <(
    find "${MODULE_CLASSES_DIR}" -type f -name '*.class' -print0 | sort -z
)

"${BUILD_TOOLS_DIR}/d8" \
    --min-api 26 \
    --lib "${ANDROID_JAR}" \
    --lib "${STUB_JAR}" \
    --output "${DEX_DIR}" \
    "${MODULE_CLASS_FILES[@]}"

"${BUILD_TOOLS_DIR}/aapt2" link \
    -o "${BUILD_DIR}/base.apk" \
    -I "${ANDROID_JAR}" \
    -A "${MODULE_DIR}/assets" \
    --manifest "${MANIFEST}" \
    --min-sdk-version 26 \
    --target-sdk-version 33

zip -q -j "${BUILD_DIR}/base.apk" "${DEX_DIR}/classes.dex"

"${BUILD_TOOLS_DIR}/zipalign" -f 4 \
    "${BUILD_DIR}/base.apk" "${BUILD_DIR}/aligned.apk"

export ZTE_INSTALLER_FIX_STOREPASS="${STORE_PASSWORD}"
export ZTE_INSTALLER_FIX_KEYPASS="${KEY_PASSWORD}"
"${BUILD_TOOLS_DIR}/apksigner" sign \
    --v4-signing-enabled false \
    --ks "${KEYSTORE}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --ks-pass env:ZTE_INSTALLER_FIX_STOREPASS \
    --key-pass env:ZTE_INSTALLER_FIX_KEYPASS \
    --out "${APK}" \
    "${BUILD_DIR}/aligned.apk"
unset ZTE_INSTALLER_FIX_STOREPASS ZTE_INSTALLER_FIX_KEYPASS
"${BUILD_TOOLS_DIR}/apksigner" verify --verbose --print-certs "${APK}"

write_checksum
printf '构建完成：%s\n' "${APK}"
