# 中兴 W200DS 原生安装器修复

Installer Fix 1.4（versionCode 5）是一个只作用于
`com.android.packageinstaller` 的 Vector/LSPosed 模块。它不替换系统 APK，
而是复用当前固件已经保留的 AOSP/CTS 安装界面和中兴对话框资源。

## 修复内容

1. 普通 APK 安装请求改走固件内置的 AOSP/CTS 用户确认流程；
2. 安装确认页恢复为悬浮对话框，并补回日夜间自适应背景和 `0.30` 遮罩；
3. 跳过会全屏闪现、但依赖组件已经缺失的中兴 `InstallScanning` 云检测页；
4. `content://` 安装仍保留 `DeleteStagedFileOnResult` 包装层，确保安装器私有
   暂存副本被清理、结果被回传；
5. 禁止固件删除用户选择的源 APK，并隐藏“已删除安装包和残留”误导文案；
6. 安装成功页恢复为悬浮页，保留原有“完成”和“打开”按钮及监听器。

完整调查证据、安全边界和真机测试见
[../../docs/INSTALLER-FIX.md](../../docs/INSTALLER-FIX.md)。

## 适用范围

当前只在以下组合完成验证：

- ZTE W200DS
- Android 13
- 系统版本 `MyOS13.0.29_W200DS_CMGEN`
- 系统安装器 `13.0.000.000.2409021359`（versionCode 130015）
- Vector，作用域 `com.android.packageinstaller/0`

厂商安装器的私有类名、资源名和方法可能随 OTA 改变。其他型号、固件或 OTA
之后应先重新核对，不要把这个模块设为全局作用域。

## 安全边界

模块跳过的是中兴额外增加的云信誉/反诈/商店限制查询，不会绕过 Android 的：

- APK 解析与签名校验；
- 更新包签名匹配；
- 未知来源授权；
- 用户安装确认；
- PackageManager 安装校验。

代价是失去厂商云端对恶意、违规或受限应用的额外判断。只安装可信来源、可核验
签名的 APK。模块自身不声明网络、存储或 Root 权限，但代码会由 Xposed 框架加载
进系统安装器进程，因此作用域必须保持最小。

## 构建

需要 JDK 17、`zip`、Android SDK platform 和 build-tools `35.0.0`：

```bash
sudo apt install openjdk-17-jdk-headless zip
sdkmanager "platforms;android-33" "build-tools;35.0.0"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/build-installer-fix.sh
```

输出：

```text
build/outputs/zte-installer-fix-v1.4.apk
build/outputs/zte-installer-fix-v1.4.apk.sha256
```

首次构建会在被 Git 忽略的 `signing/` 目录生成独立调试密钥，它只适合全新安装。
不同密钥签出的 APK 不能互相覆盖；升级平板上已经安装的 v1.4 时，必须通过
`SIGNING_KEYSTORE` 等变量继续使用原签名：

```bash
SIGNING_KEYSTORE=/secure/path/installer-fix.jks \
SIGNING_ALIAS=release \
SIGNING_STOREPASS='your-store-password' \
SIGNING_KEYPASS='your-key-password' \
./scripts/build-installer-fix.sh
```

签名文件和密码绝不能提交到仓库。

## 安装与启用

```bash
adb install -r build/outputs/zte-installer-fix-v1.4.apk
```

随后在 Vector/LSPosed 中：

1. 启用“中兴原生安装器修复”；
2. 只勾选 `com.android.packageinstaller/0`；
3. 不勾选“系统框架”或其他应用；
4. 强行停止一次“软件包安装程序”，或重启设备，再执行测试安装。

## 停用与回滚

在 Vector/LSPosed 中停用模块，然后强行停止
`com.android.packageinstaller`（或重启设备）。安装路由、云检测页和窗口样式会恢复
为固件原行为。

模块不改 system 分区，也不替换厂商 APK；但 v1.4 会把安装器私有偏好
`setting_sp/key_del_pkg` 持久化为 `false`，所以停用/卸载模块后“删除源 APK”仍可能
保持关闭。这正是保护源文件的设计，不应为了回滚 UI 而重新开启。若确实要恢复
删除行为，应只恢复安装前备份的该键原值；没有备份时不要猜测或清空整个安装器数据。
