#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk_path=${1:-"$project_dir/app/build/outputs/apk/release/app-release.apk"}

find_sdk_root() {
    for candidate in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME:-}/Android/Sdk" \
        "${HOME:-}/Library/Android/sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

find_build_tools() {
    sdk_root=$1
    # Any installed build-tools version with all three readers can inspect this API 33 APK.
    # The quoted glob preserves SDK paths containing spaces and stays within POSIX sh.
    for candidate in "$sdk_root"/build-tools/*; do
        [ -d "$candidate" ] || continue
        if [ -x "$candidate/aapt" ] \
                && [ -x "$candidate/apksigner" ] \
                && [ -x "$candidate/dexdump" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

if [ ! -f "$apk_path" ]; then
    echo "FAIL: APK not found: $apk_path" >&2
    exit 1
fi

sdk_root=$(find_sdk_root) || {
    echo "FAIL: Android SDK not found; set ANDROID_SDK_ROOT" >&2
    exit 1
}
build_tools_dir=$(find_build_tools "$sdk_root") || {
    echo "FAIL: no Android build-tools directory contains aapt, apksigner and dexdump" >&2
    exit 1
}
aapt_bin="$build_tools_dir/aapt"
apksigner_bin="$build_tools_dir/apksigner"
dexdump_bin="$build_tools_dir/dexdump"

for tool_path in "$aapt_bin" "$apksigner_bin" "$dexdump_bin"; do
    if [ ! -x "$tool_path" ]; then
        echo "FAIL: required Android build tool not found: $tool_path" >&2
        exit 1
    fi
done

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/zte-gms-guard-verify.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT HUP INT TERM

unzip -Z1 "$apk_path" > "$temp_dir/entries.txt"
if [ "$(grep -c '^classes\.dex$' "$temp_dir/entries.txt")" -ne 1 ]; then
    echo "FAIL: release must contain exactly one primary classes.dex" >&2
    exit 1
fi
if grep -q '^classes[2-9][0-9]*\.dex$' "$temp_dir/entries.txt"; then
    echo "FAIL: release unexpectedly contains secondary DEX files" >&2
    exit 1
fi

entrypoint=$(unzip -p "$apk_path" assets/xposed_init)
expected_entrypoint='io.github.dynamicfire.zte.gmsoptimizerguard.GmsOptimizerGuardHook'
if [ "$entrypoint" != "$expected_entrypoint" ]; then
    echo "FAIL: unexpected Xposed entrypoint: $entrypoint" >&2
    exit 1
fi

unzip -p "$apk_path" classes.dex > "$temp_dir/classes.dex"
"$dexdump_bin" -f "$temp_dir/classes.dex" > "$temp_dir/dexdump.txt"
if ! grep 'Class descriptor' "$temp_dir/dexdump.txt" \
        | grep -Fq "Lio/github/dynamicfire/zte/gmsoptimizerguard/GmsOptimizerGuardHook;"; then
    echo "FAIL: hook entry class is missing from primary DEX" >&2
    exit 1
fi
if grep 'Class descriptor' "$temp_dir/dexdump.txt" | grep -q "Lde/robv/android/xposed/"; then
    echo "FAIL: compile-only Xposed stubs were packaged into the APK" >&2
    exit 1
fi
if strings "$temp_dir/classes.dex" | grep -q 'compile-only stub'; then
    echo "FAIL: compile-only stub implementation text found in DEX" >&2
    exit 1
fi

"$aapt_bin" dump xmltree "$apk_path" AndroidManifest.xml > "$temp_dir/manifest.txt"
if grep -Eq 'E: uses-permission|E: (activity|service|receiver|provider)' "$temp_dir/manifest.txt"; then
    echo "FAIL: unexpected permission or Android component in manifest" >&2
    exit 1
fi
if grep 'android:debuggable' "$temp_dir/manifest.txt" \
        | grep -Eq '0xffffffff|Raw: "true"'; then
    echo "FAIL: release APK is debuggable" >&2
    exit 1
fi
grep -Fq 'A: package="io.github.dynamicfire.zte.gmsoptimizerguard"' "$temp_dir/manifest.txt"
grep -Fq 'android:versionCode' "$temp_dir/manifest.txt"
grep 'android:versionCode' "$temp_dir/manifest.txt" | grep -Fq '0x1'
grep 'android:versionName' "$temp_dir/manifest.txt" | grep -Fq '"0.1.0"'
grep 'android:minSdkVersion' "$temp_dir/manifest.txt" | grep -Fq '0x1a'
grep 'android:targetSdkVersion' "$temp_dir/manifest.txt" | grep -Fq '0x21'
awk '
    /="xposedmodule"/ {
        getline
        if ($0 ~ /android:value/ && $0 ~ /0xffffffff/) module = 1
    }
    /="xposedminversion"/ {
        getline
        if ($0 ~ /android:value/ && $0 ~ /0x5d/) minversion = 1
    }
    END { exit !(module && minversion) }
' "$temp_dir/manifest.txt" || {
    echo "FAIL: expected xposedmodule=true and xposedminversion=93 metadata" >&2
    exit 1
}

"$apksigner_bin" verify --verbose --print-certs "$apk_path" > "$temp_dir/signature.txt"
if [ -n "${EXPECTED_CERT_SHA256:-}" ]; then
    actual_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$temp_dir/signature.txt" | head -n 1 | tr 'A-F' 'a-f' | tr -d ':')
    expected_cert=$(printf '%s' "$EXPECTED_CERT_SHA256" | tr 'A-F' 'a-f' | tr -d ':')
    if [ -z "$actual_cert" ] || [ "$actual_cert" != "$expected_cert" ]; then
        echo "FAIL: signing certificate SHA-256 does not match EXPECTED_CERT_SHA256" >&2
        exit 1
    fi
fi
if command -v sha256sum >/dev/null 2>&1; then
    sha256=$(sha256sum "$apk_path" | awk '{print $1}')
else
    sha256=$(shasum -a 256 "$apk_path" | awk '{print $1}')
fi

echo "OK: single DEX, legacy entrypoint, no packaged stubs, no permissions/components, non-debuggable, signature valid"
echo "SHA-256: $sha256"
