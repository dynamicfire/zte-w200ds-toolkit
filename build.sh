#!/bin/zsh
# 编译 + 签名 PcSwitch.apk
# 用法: ./build.sh                     只编译打包
#       ./build.sh install             编译打包并安装到当前连接的设备
#       SERIAL=xxxx ./build.sh install  多设备时指定序列号（adb devices 查）
set -e
HERE=${0:a:h}
cd "$HERE"

BT=~/Library/Android/sdk/build-tools/36.0.0
AJ=~/Library/Android/sdk/platforms/android-33/android.jar
SERIAL="${SERIAL:-}"            # 留空 = 用当前唯一连接的设备

# 缺签名密钥则自动生成一个 debug keystore（口令 android）
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf classes classes.dex base.apk aligned.apk PcSwitch.apk
mkdir -p classes
javac -source 8 -target 8 -cp "$AJ" -d classes src/com/zte/mobile/*.java
"$BT/d8" --min-api 29 --output . --lib "$AJ" classes/com/zte/mobile/*.class
"$BT/aapt2" link -o base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 33 --version-code 2 --version-name 1.1
zip -j base.apk classes.dex >/dev/null
"$BT/zipalign" -f 4 base.apk aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out PcSwitch.apk aligned.apk
rm -f base.apk aligned.apk classes.dex
rm -rf classes
echo "OK -> PcSwitch.apk"

if [[ "$1" == "install" ]]; then
  if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); else ADB=(adb); fi
  "${ADB[@]}" install -r PcSwitch.apk
  echo "已安装。按 F9 测试，或: ${ADB[*]} shell am start -n com.zte.mobile/com.zte.mspice.ui.WelcomeActivity"
fi
