# ZTE W200DS：F9 自定义启动与默认平板模式

这个项目保留中兴 W200DS 原厂 F9 模式切换和触控映射，同时解决两个限制：

- 开机默认进入 MiFavor 平板桌面，不再强制显示“平板模式 / 云电脑模式”选择页。
- F9 不再绑定某一个运营商云电脑，可以从自定义列表启动任意带桌面入口的远控、云电脑或串流 App。

当前组合版本：

- F9 应用选择器：**1.8**（versionCode 9）
- APatch 默认平板模块：**1.3.0**（versionCode 4）

只在 **ZTE W200DS / Android 13 当前固件**上完成真机验证。其他批次、型号和 OTA 后的固件必须重新确认厂商组件与属性。

## 最终效果

- 平板状态开机：直接进入 MiFavor，不显示模式选择。
- 云电脑状态重启：开机完成后自动恢复并显示 MiFavor。
- 平板状态短按 F9：进入自定义应用选择器。
- 选择任意已安装的远控或云电脑 App；列表可随时编辑并跨重启保存。
- 再短按 F9：通过原厂状态机返回平板 HOME，同时恢复原厂输入映射。
- 取消选择或目标 App 启动失败：提供“返回平板模式”路径。
- 不改 system 分区，不替换厂商 APK，不需要 LSPosed。

## 组成

| 组件 | 作用 |
|---|---|
| `com.zte.mobile` APK | 占用固件在 `pc_switch_mode=0` 时保留的固定入口，显示自定义 App 列表 |
| APatch APM | 只覆盖开机模式选择属性，不停用 USmart Launcher |
| 原厂 USmart Launcher / Provider | 继续负责 HOME 状态和触控、鼠标模式切换 |

两个组件解决的问题不同：

- 只装 APK：F9 可以自定义，但开机模式选择页仍可能出现。
- 只装 APM：开机默认平板，但没有自定义 F9 应用列表，也无法处理“云状态直接重启”。
- 两者同时安装：得到本项目当前已验证的组合行为；具体范围和未覆盖项目见 [docs/TESTING.md](docs/TESTING.md)。

## 工作原理

W200DS 键盘 F9 的 Android keycode 是 `307`（`LAUNCHER_SWITCH`，观察到的扫描码为 `250`）。固件策略层直接拦截该键，先切换厂商输入模式，再调用 USmart Launcher 的模式切换逻辑；普通按键映射 App 无法在应用层接管它。

当 `Settings.System` 中的 `pc_switch_mode=0` 时，固件会显式启动：

```text
com.zte.mobile/com.zte.mspice.ui.WelcomeActivity
```

本项目用一个公开 `activity-alias` 提供该固定组件名，真正实现选择器的 `PcChooserActivity` 保持 `exported=false`。

返回平板模式时，选择器调用原厂 Provider：

```text
content://com.zte.usmartlauncher.defaulthome
method: switch_pad
```

这样由厂商状态机同步恢复 HOME 和输入映射，不是简单地强行启动桌面。

APatch 模块只覆盖：

```properties
ro.vendor.feature.zte_feature_show_smart_launcher_when_boot=false
```

这会抑制开机模式选择页，但保留 USmart Activity 和 F9 链路。模块结构遵循 [APatch APM 文档](https://apatch.dev/zh_CN/apm-guide.html)。

APK 的 `BOOT_COMPLETED` 接收器会为当前开机创建一次性恢复会话。如果持久化 HOME 明确是 USmart，它**最多调用一次**双向 `switch_mode` 拉起 MiFavor。无论接收器最初看到的是 USmart 还是 MiFavor，都会安排一个最早约 10 秒后执行的私有单次验证，以覆盖中兴固件更晚的异步 HOME 写入。

验证任务只有在“仍是本次开机会话、未被 F9 取消”，并且连续两次确认 `secure default_home=MiFavor`、实际 HOME resolver 却仍是 USmart 这个精确半状态时，才调用单向 `switch_pad` 固化系统 HOME；其他状态全部只消费任务并退出。F9 选择器在显示前就会作废本次恢复会话。当前固件的 `switch_pad` 不启动 Activity，真机在其他 App 前台调用也不会抢走界面。这个 Alarm 是免额外权限的 inexact alarm，系统可能晚于 10 秒投递。

如果系统无法读取 `Settings.Global.BOOT_COUNT`，接收器会安全跳过自动恢复，不会跨开机猜测或复用旧会话；当前实测的 W200DS 固件可以正常读取该值。

更完整的固件观察和设计取舍见 [docs/W200DS-ADAPTATION.md](docs/W200DS-ADAPTATION.md)。

## 权限与安全边界

Manifest 中唯一的 Android 权限是：

```text
android.permission.RECEIVE_BOOT_COMPLETED
```

应用通过 Android 11+ 的 `<queries>` 只声明可见的 `MAIN/LAUNCHER`、`MAIN/HOME` Activity 和中兴 Provider，不再申请 `QUERY_ALL_PACKAGES`。

应用不申请：

- 网络权限
- Root 权限
- 无障碍权限
- 悬浮窗权限
- 存储权限
- 设备管理权限

它也没有常驻服务。所选应用列表只保存在 APK 私有的 `SharedPreferences` 中。

## 前提

- 中兴 W200DS，Bootloader 已解锁。
- 已能通过 ADB 连接。
- 已有可用 Root；默认平板模块按 APatch APM 格式提供。
- 原厂 `com.zte.usmartlauncher` 仍存在。

安装前先记录本机原值。不同固件可能是 `1`、`4` 或其他值，不要把别人的值当成自己的恢复值：

```bash
adb shell settings get system pc_switch_mode
adb shell settings get secure default_home
```

## 构建 APK

需要 JDK 17、`zip`、`unzip`、Android SDK platform，以及 Android build-tools `35.0.0`。`sdkmanager` 由 Android 官方 [Command-line Tools](https://developer.android.com/tools) 提供。推荐：

```bash
sudo apt install openjdk-17-jdk-headless zip unzip
sdkmanager "platforms;android-33" "build-tools;35.0.0"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./build.sh
```

输出位于：

```text
build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk
build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk.sha256
```

首次本地构建会在 `signing/` 生成仅供侧载测试的 debug keystore，该目录已被 Git 忽略。不同 clone 自动生成的密钥不同，APK 不能互相覆盖升级。

正式持续发布时应使用离线保存的固定签名：

```bash
SIGNING_KEYSTORE=/secure/path/release.jks \
SIGNING_ALIAS=release \
SIGNING_STOREPASS='your-store-password' \
SIGNING_KEYPASS='your-key-password' \
./build.sh
```

签名密钥和密码绝不能提交到仓库。

## 构建 APatch 模块

```bash
./scripts/build-apm.sh
```

输出位于：

```text
build/outputs/zte-w200ds-tablet-default-apm-v1.3.0.zip
build/outputs/zte-w200ds-tablet-default-apm-v1.3.0.zip.sha256
```

模块源码和独立说明在 [apm/zte_w200ds_tablet_boot](apm/zte_w200ds_tablet_boot/)。

## 安装

1. 重新启用原厂 USmart 主 Activity。此前为了跳过开机选择而停用过它的设备尤其需要这一步：

```bash
adb shell su -c 'pm enable --user 0 com.zte.usmartlauncher/.activity.UsmartMainActivity'
```

2. 安装或升级选择器：

```bash
adb install -r build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk
```

3. 把 F9 指向固件的自定义空槽：

```bash
adb shell su -c 'settings put system pc_switch_mode 0'
```

4. 在 APatch 管理器中安装 `zte-w200ds-tablet-default-apm-v1.3.0.zip`，然后重启。

5. 重启后短按一次 F9，确认选择器正常出现。首次显式启动也会解除 Android 对新装应用的 stopped 状态，之后才能正常收到开机广播。

## 使用

- F9 打开“F9 云模式：选择要启动的应用”。
- “编辑可选应用…”会列出所有带 `MAIN/LAUNCHER` 入口的已安装 App。
- 勾选并保存后，设置跨重启保留。
- “返回平板模式”、返回键或点对话框外部都会请求原厂 `switch_pad`。
- 进入目标 App 后，再按 F9 返回平板模式。

`pc_switch_mode` 必须保持为 `0`，否则固件会绕过本选择器。

## 完整回滚

先恢复安装前记录的本机原值。下面的 `RECORDED_VALUE` 只是占位符，不能原样执行：

```bash
adb shell su -c 'settings put system pc_switch_mode RECORDED_VALUE'
adb uninstall com.zte.mobile
adb shell su -c 'touch /data/adb/modules/zte_w200ds_tablet_boot/disable'
adb reboot
```

如果安装前读到的是 `null`，第一行应改为：

```bash
adb shell su -c 'settings delete system pc_switch_mode'
```

重启后 APatch 属性覆盖失效，原厂开机逻辑恢复。模块目录仍保留，可以在 APatch 管理器中重新启用或彻底删除。

## 已知边界

- 实体 F9 短按与 `adb shell input keyevent 307` 走相同 keycode 分支；长按行为仍由固件控制，本项目没有重映射长按。
- 如果在系统设置中“强行停止”选择器，Android 会在下次显式启动前阻止开机广播。短按一次 F9 打开它即可解除 stopped 状态。
- Android 或中兴省电策略可能延迟 `BOOT_COMPLETED`；出现问题时把选择器电池策略保持为“优化”或“不受限制”。
- 开机 HOME 验证使用免特殊权限的 inexact alarm；“约 10 秒”是最早触发时间，省电或系统批处理可能让它更晚执行。
- “可选择任意 App”只代表能启动其 LAUNCHER Activity，不保证目标 App 自身适配横屏、右键、滚轮或中兴输入映射。
- OTA 可能替换 Launcher、Provider 或属性。升级后应重新执行 [docs/TESTING.md](docs/TESTING.md) 中的关键测试。
- 若已有不同签名的 `com.zte.mobile`，Android 不允许直接覆盖；卸载前先确认其中是否有需要保留的数据。

## 仓库结构

```text
.
├── AndroidManifest.xml
├── build.sh
├── src/com/zte/mobile/
│   ├── PcChooserActivity.java
│   └── TabletBootReceiver.java
├── apm/zte_w200ds_tablet_boot/
├── scripts/build-apm.sh
├── docs/W200DS-ADAPTATION.md
├── docs/TESTING.md
└── CHANGELOG.md
```

发布前检查清单和本次真机结果见 [docs/TESTING.md](docs/TESTING.md)。
