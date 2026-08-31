# 发布前测试

下面的检查面向 W200DS 当前固件。涉及模式切换时，先确保屏幕亮起并已解锁；两次 F9 自动化事件之间至少等待 1.5 秒。

## 1. 源码与敏感文件

```bash
git status --short
git ls-files | grep -Ei '(\.keystore|\.jks|\.apk|\.idsig|\.img|\.bin)$'
git check-ignore signing/debug.keystore build/outputs/example.apk example.img example.bin
```

期望：私钥、APK、设备镜像和构建目录都不在 Git 跟踪列表中，且会被 `.gitignore` 命中。

## 2. APK 构建与签名

```bash
./build.sh
(cd build/outputs && \
  sha256sum -c zte-w200ds-f9-app-chooser-v1.8.apk.sha256)
apksigner verify --verbose --print-certs \
  build/outputs/zte-w200ds-f9-app-chooser-v1.8.apk
```

确认：

- versionName `1.8`，versionCode `9`。
- 只有 `RECEIVE_BOOT_COMPLETED` 权限。
- `PcChooserActivity exported=false`。
- 固件兼容 alias `exported=true`，且没有桌面图标过滤器。
- `TabletBootReceiver exported=false`。
- `<queries>` 只覆盖 `MAIN/LAUNCHER`、`MAIN/HOME` 和中兴 Provider。
- APK 至少通过 Android 10 所支持的 APK Signature Scheme v3。

升级测试必须使用与设备现有 `com.zte.mobile` 相同的签名。

## 3. APM 构建

```bash
./scripts/build-apm.sh
(cd build/outputs && \
  sha256sum -c zte-w200ds-tablet-default-apm-v1.3.0.zip.sha256)
unzip -Z1 build/outputs/zte-w200ds-tablet-default-apm-v1.3.0.zip
```

ZIP 根目录只应有：

```text
module.prop
system.prop
skip_mount
README.md
```

不得出现 `service.sh`、`boot-completed.sh`、私钥、镜像或本机路径。

## 4. 设备基线

```bash
adb get-state
adb shell su -c id
adb shell getprop sys.boot_completed
adb shell getprop ro.vendor.feature.zte_feature_show_smart_launcher_when_boot
adb shell settings get system pc_switch_mode
adb shell settings get secure default_home
adb shell cmd shortcut get-default-launcher --user 0
```

完整方案的平板基线应为：

- `sys.boot_completed=1`。
- 属性为 `false`。
- Root 可用。
- `pc_switch_mode=0`。
- 持久化 HOME 和默认 Launcher 都是 MiFavor。

## 5. 选择器

1. 平板桌面短按实体 F9。
2. 确认打开自定义选择器，而不是运营商固定云电脑。
3. “编辑可选应用…”中能看到已安装的 LAUNCHER App。
4. 保存列表、结束选择器、再次打开，选择仍保留。
5. 测试“返回平板模式”、返回键和点击对话框外部。
6. 选择至少一个第三方远控 App，确认能启动。
7. 在目标 App 中短按 F9，确认返回 MiFavor。
8. 连续执行“F9 → 不选应用 → F9 → 再次 F9”，确认没有旧选择器 Activity 残留。

自动化短按可使用：

```bash
adb shell input keyevent 307
sleep 1.5
adb shell input keyevent 307
```

该命令可覆盖相同 keycode 的短按策略分支，但发布前仍应使用实体 F9 做一次交叉验证；长按只能用实体键验证。

## 6. 输入映射

平板返回后先唤醒并解锁，再检查：

```bash
adb shell dumpsys power | grep -E 'mWakefulness=|mInteractive='
adb shell dumpsys input
```

当前固件验收值：

```text
himax-touchscreen -> DIRECT
ztp_input          -> POINTER
```

熄屏下的 `DISABLED` 不算失败。

## 7. 重启矩阵

### 平板状态重启

- 重启前 HOME 为 MiFavor。
- 重启后不出现模式选择页。
- 可见前台、逻辑 HOME 和默认 Launcher 都回到 MiFavor。
- F9 往返仍正常。

### 云状态重启

- 先用 F9 进入选择器或目标云 App，确认 `default_home` 为 USmart。
- 不按 F9 返回，直接重启。
- `BOOT_COMPLETED` 后最终可见前台必须是 MiFavor。
- 逻辑 HOME、默认 Launcher 和输入映射同时恢复。
- 每次开机最多调用一次 `switch_mode`。
- 即使接收器最初看到稳定 MiFavor，也应只安排一次 guarded verification；只有 `secure=MiFavor + resolver=USmart` 半状态才调用 `switch_pad`。
- `dumpsys activity broadcasts` 能看到私有 `com.zte.mobile.action.FINALIZE_TABLET_HOME`，恢复后按 HOME 仍停在 MiFavor。
- 验证任务排队期间主动按 F9，等待超过触发窗口后仍应保持 USmart，不得被旧任务回拉。

### 冷启动

完成一次真正关机再开机，重复平板状态验收，确认没有模式选择页闪现。

## 8. Android stopped 边界

对选择器执行“强行停止”后重启，预期 Android 不投递开机广播。短按 F9 显式启动一次后再重启，应恢复广播投递。这个行为需要写进发布说明，不能伪装成应用故障。

## 9. 完整回滚

使用安装前保存的本机 `pc_switch_mode` 原值，完成 README 中的卸载、模块禁用和重启步骤。确认：

- 属性恢复固件值。
- 原厂开机模式逻辑恢复。
- 原厂 F9 目标恢复。
- USmart Activity 仍为 enabled。

## 10. 当前实测结论

2026-08-29 在 W200DS / Android 13 上已经通过：

本轮结果绑定到以下最终发布候选，校验文件由构建脚本同时生成：

```text
APK SHA-256:  b37a98fe493c8c070bce61f40ac8e1f051837727385402b42378d4962c9b4249
APM SHA-256:  48089547f97fffc3fe8542164a7c97222bd13c55b0564c3870fc7a21c88439ba
证书 SHA-256: 734b4a48110ff81c27f2c345276676d38ec6b3e6a8eeae267f1ba1b40fe051bd
```

- 上述 APK 同签名升级安装为 v1.8 / versionCode 9；Manifest 只有 `RECEIVE_BOOT_COMPLETED`，alias 无桌面图标。
- 上述 APM 1.3.0 属性覆盖、模块启用和无选择页启动；APatch Root 保持可用。
- F9 keycode 307 双向往返，选择器显示、12 个 LAUNCHER 应用编辑项、原厂/第三方应用启动路径和返回 MiFavor。
- `noHistory` 回归：返回平板后选择器任务不残留。
- 云状态真实重启：观察到厂商晚写造成的 MiFavor/USmart 半状态，私有 finalizer 随后投递并把 Secure、实际默认 Launcher、可见前台全部恢复为 MiFavor。
- 平板状态真实重启：guarded verification 投递一次并消费恢复会话，Secure HOME 与实际 Launcher 始终保持 MiFavor。
- HOME 键回归：恢复后按 HOME 仍停在 MiFavor。
- 在“设置”等其他 App 保持前台时调用原厂 `switch_pad`，前台未被抢走；与反编译确认其只更新 HOME/设置、不启动 Activity 的结果一致。
- 定向重放本应用的 `BOOT_COMPLETED` 后确认私有 Alarm 与 current-boot token 已排队；用户主动 F9 进入云模式会同时清除 token 并得到系统 `alarm_cancelled` 记录。等待 15 秒后 Secure、默认 Launcher 和选择器前台都保持 USmart；再次 F9 正常回 MiFavor。
- 平板状态下 `himax-touchscreen` 为 DIRECT；键盘已枚举时 `ztp_input` 的 Android input source 为鼠标 `0x2002`（POINTER）。

本次最终构建尚未重新执行真正断电的冷启动、实体 F9 长按，以及必须实体操作的右键和滚轮。发布时不得把这些项目写成已完成。

## 11. Installer Fix 1.4 回归

Installer Fix 与 F9/APM 是独立组件。构建前确认仓库中只有源码和 compile-only
Xposed stub，不包含系统 framework、厂商 APK/反编译树、Vector 数据库、签名密钥、
截图或测试探针。

```bash
./scripts/build-installer-fix.sh
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  build/outputs/zte-installer-fix-v1.4.apk
"$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" dump permissions \
  build/outputs/zte-installer-fix-v1.4.apk
```

验收点：

- Manifest 为 versionCode 5 / versionName 1.4，APK v2/v3 签名通过；
- 模块 APK 不声明网络、存储、Root 等 Android 权限；
- Vector 作用域只有 `com.android.packageinstaller/0`，不包含系统框架；
- 从普通文件路径发起安装，悬浮确认页和悬浮成功页正常；
- 从 `content://` URI 发起安装，来源应用在 `0.30` 遮罩下可见；
- 不出现全屏 `InstallScanning`，也不再访问缺失的 HeartyService；
- “完成”和“打开”按钮保持原行为；
- 用户源 APK 保留，“已删除安装包和残留”文案不再显示；
- 当前流程创建的安装器私有 staged APK 在完成后被
  `DeleteStagedFileOnResult` 清理；不要批量删除仍可能关联旧 Session 的历史文件；
- 停用模块并强行停止安装器后，安装路由、云检测页和窗口恢复；持久化的
  `key_del_pkg=false` 不会自动恢复，不能把这一项记为完整回滚。

2026-08-30 在 W200DS / Android 13 / 系统版本
`MyOS13.0.29_W200DS_CMGEN`、系统安装器
`13.0.000.000.2409021359`（versionCode 130015）上已完成上述可见流程与 staged
清理测试。实机测试使用的
v1.4 APK SHA-256 为：

```text
e41a49d9e8a6636297eac6b0b428fb58ff25a419929f64778b72b98ae2acf0b0
```

其签名证书 SHA-256 为：

```text
7a511fc187a9f3ccd21cc0da66ae72de0c47424b9c2a119549229fd9d485f749
```

这个 APK 哈希绑定当次构建与签名，不代表其他本地调试密钥生成的 APK。完整调查、
安全取舍和回滚见 [INSTALLER-FIX.md](INSTALLER-FIX.md)。

## 12. GMS Optimizer Guard 0.1.0 回归

该模块与 F9、默认平板 APM、Clash Watchdog 和 Installer Fix 都是独立组件。源码只匹配
SDK 33、incremental `20250218.231611` 与以下完整 fingerprint：

```text
ZTE/CN_P720P01/P720P01:13/TP1A.220624.014/20250218.231611:user/release-keys
```

### 静态构建门

```bash
cd xposed/zte_gms_optimizer_guard
gradle --offline clean testDebugUnitTest lintRelease assembleRelease
./tools/verify-release.sh app/build/outputs/apk/release/app-release.apk
```

若依赖尚未缓存，首次构建去掉 `--offline`，只从项目声明的 Google/Maven Central/Gradle Plugin
Portal 解析 AGP 8.7.3 与 JUnit 4.13.2。验收必须确认：

- 4 个策略/固件门测试全部通过；
- lint 0 error；固定 SDK、无界面/图标等设备专用 warning 可记录但不能隐藏；
- APK 只有一个 `classes.dex`，legacy `assets/xposed_init` 入口位于主 DEX；
- compile-only `de.robv.android.xposed.*` stub 没有被打进 APK；
- 无权限、Activity、Service、Receiver 或 Provider，且 APK 非 debuggable；
- 签名有效；覆盖设备上已有 v0.1.0 时必须继续使用同一证书。

仓库卫生还应额外检查：

```bash
git ls-files | grep -E '(^|/)(\.gradle|build)/|(^|/)local\.properties$|\.(apk|aab|apks|idsig|dex|class|jar|keystore|jks|p12|pfx|pem|key)$'
```

期望不出现 GMS Guard 的构建目录、APK、签名、系统 `services.jar`、反编译树、Vector 数据库、
测试 UI dump 或其他实机取证文件。

### 当前真机证据

2026-08-31 在上述精确固件、Vector 2.2 / 3080 / API 102、唯一作用域 `system/0` 上已确认：

- 模块包 `io.github.dynamicfire.zte.gmsoptimizerguard` v0.1.0 安装并启用；
- 重启约 28 秒完成，`system_server` 同一 PID 稳定超过 15 分钟，无 system_server fatal、
  Watchdog/ANR、partial rollback 或 callback retries exhausted；
- 新增由 UID 1000 注册的 VPN-only `NetworkRequest`，与模块
  `clearCapabilities().addTransportType(TRANSPORT_VPN)` 路径一致；
- Clash Android VPN 和 `latchsky_enable=1` 同时成立时，IPv4/IPv6 `zte_fw_gms` 均保持
  `RETURN`，15 分钟厂商任务窗口没有重新生成 Play/GMS UID DROP；
- Play UID 10205 请求 `play.googleapis.com/generate_204`、GMS UID 10235 请求
  `android.clients.google.com/generate_204` 均收到 HTTP 204，并经 Clash Fake-IP 完成；
- Google Play 更新页、`YouTube` 搜索结果和应用详情页均正常加载，没有离线/重试提示；
- 未点击安装，没有下载/更新应用，也没有更改 Google 账号；
- 屏幕关闭时 OEM 通用 CPU freezer 仍会冻结 Play UID，亮屏后因 `screen_on` 解冻，证明模块
  没有关闭整套后台省电，但这不能替代耗电测量。

当次实机候选 APK SHA-256：

```text
af279aa3b607f744643a288db110f50f6c8abb467199b59938364161f3e513ef
```

该哈希绑定当次本地测试签名和 ZIP 元数据，仓库不提交 APK。后续重新构建只应比较源码版本、
签名身份和静态结构，不能要求不同构建环境生成相同 APK 哈希。

### 尚未完成

- Play 真实下载/更新与后台恢复；
- 连续切换节点十次的 30 秒宽限回归；
- VPN 关闭超过宽限后重新进入原厂 `updateCheckStrategy()`；
- 30/60 分钟厂商任务和 70 分钟持续 VPN 守护；
- 同等负载下 8–24 小时 GMS wakelock、CPU、闹钟及待机掉电 A/B。

在这些项目完成前，只能声称当前模块阻止了 VPN 活跃时的误封并保留普通省电机制，不能声称
已经量化省电收益或完整验证 VPN 断开后的专项策略恢复。完整安装、救援和回滚步骤见
[../xposed/zte_gms_optimizer_guard/INSTALL.md](../xposed/zte_gms_optimizer_guard/INSTALL.md)，规则来源见
[GMS-FIREWALL-FORENSICS.md](GMS-FIREWALL-FORENSICS.md)。
