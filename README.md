# ZTE W200DS 平板改造工具集

这个仓库最早只是为了把 W200DS 的 F9 从固定云电脑入口改成自选应用，后来又陆续加进了默认平板启动、Clash 保活、安装器修复、GMS 网络修复和 Ubuntu/KVM 工具。

它不是一个必须整套安装的“全家桶”。如果你只想改 F9，看 F9 选择器和默认平板 APM 就够了；其他模块互不依赖，按需安装。

## 先找你要的功能

| 想解决的问题 | 组件 | 当前版本 | 需要什么 |
|---|---|---:|---|
| F9 打开自己选的远控、云电脑或串流 App | `com.zte.mobile` F9 选择器 | 1.8（versionCode 9） | ADB；设置模式时需要 Root |
| 开机直接进 MiFavor，不显示模式选择页 | [默认平板 APM](apm/zte_w200ds_tablet_boot/) | 1.3.0（versionCode 4） | APatch |
| Clash Meta 被系统杀掉后自动恢复 VPN | [Clash Meta Watchdog](docs/CLASH-META-WATCHDOG.md) | 1.0.2（versionCode 3） | APatch，加上设备端白名单设置 |
| 恢复原生悬浮安装界面，并保留源 APK | [Installer Fix](docs/INSTALLER-FIX.md) | 1.4（versionCode 5） | Vector/LSPosed |
| Clash/VPN 开着时，避免厂商策略误封 Play/GMS | [GMS Optimizer Guard](xposed/zte_gms_optimizer_guard/INSTALL.md) | 0.1.0（versionCode 1） | Vector/LSPosed，严格固件校验 |
| 在平板上跑 ARM64 Ubuntu Server | [Ubuntu/KVM 工具](docs/UBUNTU-KVM.md) | Ubuntu 26.04 LTS；QEMU 11.0.3 | Termux、APatch Root、KVM |

F9 选择器和默认平板 APM 是一组，但也能分开用：

- 只装 APK：F9 可以自定义，开机模式选择页仍可能出现。
- 只装 APM：开机默认进平板模式，但没有自定义 F9 列表，也处理不了“云电脑状态直接重启”。
- 两个都装：就是目前真机验证过的完整 F9/默认平板方案。

## 兼容范围

目前的真机结果来自 **ZTE W200DS / P720P01、Android 13 当前固件**。W202DS 我没有直接拿到机器测试；它和 W200DS 的固件通用，但这里仍按“未实机验收”处理。

GMS Optimizer Guard 的限制更严：设备必须报告内部型号 `P720P01`、SDK 33、incremental `20250218.231611`，并命中源码里固定的完整 fingerprint。任意一项不符，模块都会保持不挂钩。

不同批次、其他型号以及 OTA 后的固件，都应该重新确认厂商 Launcher、Provider、属性和 Hook 目标。已有测试记录在 [docs/TESTING.md](docs/TESTING.md)。

## F9 + 默认平板：最常用的一套

装好以后：

- 开机直接进入 MiFavor，不再先问“平板模式 / 云电脑模式”。
- 短按 F9 打开自定义应用列表。
- 再按一次 F9，通过原厂状态机回到平板 HOME，同时恢复原厂输入映射。
- 选中的应用列表会保存在应用自己的私有数据里，重启后还在。
- 取消选择或目标 App 启动失败时，仍然可以返回平板模式。

这里没有替换系统 APK，也没有停用 USmart Launcher。选择器借用了固件在 `pc_switch_mode=0` 时保留的固定入口：

```text
com.zte.mobile/com.zte.mspice.ui.WelcomeActivity
```

对外公开的只是这个固定 `activity-alias`，真正的 `PcChooserActivity` 仍是 `exported=false`。

W200DS 的 F9 keycode 是 `307`（`LAUNCHER_SWITCH`，观察到的扫描码为 `250`）。返回平板模式时，应用调用原厂 Provider 的 `switch_pad`：

```text
content://com.zte.usmartlauncher.defaulthome
method: switch_pad
```

默认平板 APM 只覆盖这一项属性：

```properties
ro.vendor.feature.zte_feature_show_smart_launcher_when_boot=false
```

这样可以隐藏开机模式选择页，同时保留 USmart Activity 和 F9 链路。开机 HOME 的状态判断、一次性验证和异常处理见 [docs/W200DS-ADAPTATION.md](docs/W200DS-ADAPTATION.md)。

### 权限

F9 选择器唯一申请的 Android 权限是：

```text
android.permission.RECEIVE_BOOT_COMPLETED
```

它没有网络、Root、无障碍、悬浮窗、存储或设备管理权限，也没有常驻服务。可选应用列表只保存在私有 `SharedPreferences` 中。

### 安装前先记下原值

你需要一台已经解锁 Bootloader、能用 ADB 和 Root 的 W200DS，并保留原厂 `com.zte.usmartlauncher`。

先执行下面两条，把结果记下来。`pc_switch_mode` 在不同固件上可能是 `1`、`4` 或其他值，回滚时必须使用你自己机器的原值。

```bash
adb shell settings get system pc_switch_mode
adb shell settings get secure default_home
```

### 构建

F9 APK 需要 JDK 17、`zip`、`unzip`、Android SDK platform 33 和 build-tools `35.0.0`。`sdkmanager` 来自 Android 官方 [Command-line Tools](https://developer.android.com/tools)。下面的 `apt` 命令适用于 Debian/Ubuntu；macOS 请按本机方式准备这些依赖。

```bash
sudo apt install openjdk-17-jdk-headless zip unzip
sdkmanager "platforms;android-33" "build-tools;35.0.0"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./build.sh
```

输出：

```text
build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk
build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk.sha256
```

默认平板 APM：

```bash
./scripts/build-apm.sh
```

输出：

```text
build/outputs/zte-w200ds-tablet-default-apm-v1.3.0.zip
build/outputs/zte-w200ds-tablet-default-apm-v1.3.0.zip.sha256
```

首次构建 F9 APK 时，会在被 Git 忽略的 `signing/` 目录生成 debug keystore。不同 clone 生成的密钥不同，APK 不能互相覆盖升级。要长期发布，请改用离线保存的固定签名：

```bash
SIGNING_KEYSTORE=/secure/path/release.jks \
SIGNING_ALIAS=release \
SIGNING_STOREPASS='your-store-password' \
SIGNING_KEYPASS='your-key-password' \
./build.sh
```

签名密钥和密码不要提交到仓库。

### 安装

1. 重新启用原厂 USmart 主 Activity。以前为了跳过选择页而停用过它的设备尤其要做这一步。

```bash
adb shell su -c 'pm enable --user 0 com.zte.usmartlauncher/.activity.UsmartMainActivity'
```

2. 安装或升级 F9 选择器。

```bash
adb install -r build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk
```

3. 把 F9 指向固件的自定义空槽。

```bash
adb shell su -c 'settings put system pc_switch_mode 0'
```

4. 在 APatch 管理器里安装 `zte-w200ds-tablet-default-apm-v1.3.0.zip`，然后重启。

5. 重启后短按一次 F9，确认选择器能正常出现。第一次显式启动也会解除 Android 对新装应用的 stopped 状态，之后它才能收到开机广播。

### 怎么用

- F9 打开“F9 云模式：选择要启动的应用”。
- 点“编辑可选应用…”选择带 `MAIN/LAUNCHER` 入口的 App。
- 点“返回平板模式”、按返回键或点对话框外部，都会请求原厂 `switch_pad`。
- 进入目标 App 后，再按 F9 返回平板模式。

`pc_switch_mode` 必须保持为 `0`，否则固件会绕过这个选择器。

### 恢复原状

先把 `pc_switch_mode` 恢复成安装前记下的值。下面的 `RECORDED_VALUE` 是占位符，**不能原样执行**。

`adb uninstall com.zte.mobile` 会清空选择器数据。如果设备上原本就有同名包，不要直接照抄，先确认包的来源并备份需要保留的内容。

```bash
adb shell su -c 'settings put system pc_switch_mode RECORDED_VALUE'
adb uninstall com.zte.mobile
adb shell su -c 'touch /data/adb/modules/zte_w200ds_tablet_boot/disable'
adb reboot
```

如果安装前读到的是 `null`，第一行改成：

```bash
adb shell su -c 'settings delete system pc_switch_mode'
```

重启后 APatch 的属性覆盖就会失效，原厂开机逻辑恢复。模块目录仍会保留，可以在 APatch 管理器里重新启用或删除。

## 其他工具

### Clash Meta Watchdog

这个 APM 只管 Clash for Android/Meta 意外退出，不参与 F9 或 HOME 切换。当前固件还要配合三项设置：

- 锁定 Clash 的最近任务卡片。
- 把 Clash 加进中兴 `used_module=6` 的“仅移除任务”窄白名单。
- 打开 Android 的“始终开启 VPN”，但不要打开“阻止不使用 VPN 的连接”。

从应用内正常 Stop，或在系统设置里“强行停止”，都不会被重新拉起。只有进程意外死亡时才会兜底恢复；旧连接保不住，中间会断流几秒。

构建用 `./scripts/build-clash-watchdog-apm.sh`。完整安装、验证和回滚见 [docs/CLASH-META-WATCHDOG.md](docs/CLASH-META-WATCHDOG.md)。

### Installer Fix

Installer Fix 的作用域固定在 `com.android.packageinstaller/0`。它把固件里还在的 AOSP/CTS 安装界面重新接回来，Android 的签名、未知来源和用户确认照常保留。

当前已测固件上的中兴云信誉/反诈层缺少依赖组件，因此会被跳过；代价是失去这层额外风险信号。只安装可信来源并核验过签名或哈希的 APK。

构建用 `./scripts/build-installer-fix.sh`。作用域、安装、真机结果和回滚见 [docs/INSTALLER-FIX.md](docs/INSTALLER-FIX.md)。

### GMS Optimizer Guard

在当前固件上查到，ZTE `GoogleOptimizer` 可能会通过 `zte_fw_gms`，把 Play/GMS 的 IPv4/IPv6 流量挡在 VPN 之前。这也是为什么 Clash 节点本身正常，Google Play 仍可能一直连不上。

模块只在 Android VPN 活跃以及断开后的 30 秒宽限内阻止这套 Google 专项策略重新落下。它识别的是 Android `VpnService` 暴露的 VPN transport；没有注册成 Android VPN 的 root TUN/TProxy 不一定会触发保护。VPN 活跃时，Google 专项网络限制、闹钟对齐和相关冻结会暂停，这是可用性和专项省电之间的取舍。

模块运行在 `system_server`，风险比普通应用高。作用域只能选 `system/0`，不能选 `android/0`；安装前先准备好 Android 安全模式或 recovery 救援路径。完整清单见 [INSTALL.md](xposed/zte_gms_optimizer_guard/INSTALL.md)，规则来源见 [GMS-FIREWALL-FORENSICS.md](docs/GMS-FIREWALL-FORENSICS.md)。

### Ubuntu/KVM

这套脚本通过 Termux、Root QEMU 和 `/dev/kvm` 运行无 GUI 的 Ubuntu Server 26.04。它沿用原厂的 KVM 权限和 SELinux 策略，不改 system 分区，也不设开机自启。设备端和 Mac 端都用 `u26` 作为日常入口；私钥、VM 镜像、恢复点和运行状态留在本地。

准备、安装、验收和恢复步骤见 [docs/UBUNTU-KVM.md](docs/UBUNTU-KVM.md)。

## 已知限制

- F9 长按仍由固件控制；本项目只处理短按。
- 如果在系统设置里“强行停止”选择器，Android 会阻止它接收开机广播，直到你再次短按 F9 显式启动。
- 开机 HOME 验证使用 inexact alarm，“约 10 秒”只是最早触发时间，省电或系统批处理可能让它更晚。
- 能启动一个 App，不代表这个 App 一定适配横屏、右键、滚轮或中兴输入映射。
- 已有不同签名的 `com.zte.mobile` 时不能直接覆盖；卸载前先确认旧应用里有没有要保留的数据。
- OTA 可能改变 Launcher、Provider、属性或私有类名，升级后应重新跑关键测试。
- GMS Optimizer Guard 能正常加载，Play/GMS 的 UID HTTP 204 以及 Play 更新页、搜索、详情页都测过了。真实下载/更新、VPN 断开 30 秒后的恢复、70 分钟守护和 8–24 小时功耗对比还没跑完。
- Ubuntu/KVM 目前只验证了 2 vCPU、1536 MiB、64 GiB 稀疏盘和 SSH-first 运行，没有 GPU 加速、自动启动或完整桌面验收。

## 仓库结构

```text
.
├── AndroidManifest.xml              # F9 选择器
├── build.sh
├── src/com/zte/mobile/
├── apm/                             # 默认平板和 Clash Watchdog
├── xposed/                          # Installer Fix 和 GMS Guard
├── scripts/                         # 构建脚本
├── tools/ubuntu-kvm/                # Ubuntu/KVM 设备端与 Mac 端工具
├── docs/                            # 安装、取证、测试和回滚说明
└── CHANGELOG.md
```

各组件的安装和回滚都在对应文档里；F9/默认平板的测试范围和发布检查见 [docs/TESTING.md](docs/TESTING.md)。
