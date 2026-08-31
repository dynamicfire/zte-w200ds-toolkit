# 安装、验收与回滚

## 当前状态

2026-08-31 已在匹配固件的 P720P01 上完成首次安装、启用和重启测试：

- 包 UID：`10349`；
- Vector 2.2 状态：`enabled`；
- 唯一作用域：`system/0`；
- 设备内 APK SHA-256 与本地交付 APK 完全一致；
- 首次启动约 28 秒完成，`system_server` 同一 PID 已稳定超过 15 分钟；
- Clash VPN 活跃且 `latchsky_enable=1` 时，IPv4/IPv6 `zte_fw_gms` 均保持 `RETURN`；
- Play UID 10205 与 GMS UID 10235 的无凭据 HTTPS 探测均收到 Google `HTTP 204`。
- Google Play 更新页、搜索结果和应用详情页均已正常加载，未出现离线/重试提示。

未点击“安装”或执行下载/更新。VPN 断开后的 30 秒恢复、70 分钟守护和 8–24 小时功耗 A/B
仍是后续验收项。

模块包名：

```text
io.github.dynamicfire.zte.gmsoptimizerguard
```

Vector 唯一正确的 system_server 作用域：

```text
system/0
```

不要设置成 `android/0`。

## 安装前

1. 确认当前仍是 SDK 33、incremental `20250218.231611` 和已审计的完整 build fingerprint。
2. 确认 Vector 2.2/3080 正常，ADB 与 root shell 都可用。
3. 确认能通过实体键进入 Android 安全模式，或有可用 recovery；这是 system_server 模块的救援门。
4. 保持“应用优化”关闭做第一次启动，以便先验证模块加载，不同时引入原厂封锁策略。

## 已验证候选与后续安装示例

2026-08-31 实机安装的文件名为 `ZTE-GMS-Optimizer-Guard-v0.1.0-local.apk`，SHA-256 为：

```text
af279aa3b607f744643a288db110f50f6c8abb467199b59938364161f3e513ef
```

仓库不提交这个 APK。下面的 `app-release.apk` 是从同一源码重新构建后的本地输出路径；不同 ZIP
元数据会产生不同 APK 哈希，不能把它直接写成上述已验证文件。覆盖升级前还必须确认签名证书一致。

后续本地构建的安装与作用域设置示例：

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell "su -c '/data/adb/lspd/cli scope set io.github.dynamicfire.zte.gmsoptimizerguard system/0'"
adb shell "su -c '/data/adb/lspd/cli modules enable io.github.dynamicfire.zte.gmsoptimizerguard'"
adb shell "su -c '/data/adb/lspd/cli modules ls; /data/adb/lspd/cli scope ls io.github.dynamicfire.zte.gmsoptimizerguard'"
```

上述 Vector 作用域、启用和回读命令已在当前实机确认。重新安装、启用、改作用域或重启都属于设备
写操作；执行前应再次确认设备指纹、APK 签名和可用的安全模式/recovery 救援路径。

## 分阶段验收

### A. 安全加载

首次重启后检查：

```sh
adb shell "su -c '/data/adb/lspd/cli status; /data/adb/lspd/cli modules ls; /data/adb/lspd/cli scope ls io.github.dynamicfire.zte.gmsoptimizerguard'"
adb shell "logcat -d -s ZteGmsOptGuard:I GoogleOptimizer:I '*:S'"
```

本机的早期普通信息日志没有保留在当前缓冲区，但 `system_server` 新增的 VPN-only `NetworkRequest`
可作为 callback 已注册的直接运行证据。不能出现 system_server 重启、连续异常或 ANR。

### B. 功能

1. 开启 Clash 的 Android VPN 模式。
2. 再通过系统 UI 打开“应用优化”。
3. 确认 `system_server` 存在模块注册的 VPN-only `NetworkRequest`，且没有模块异常；若设备允许 Info 日志，
   可额外观察 `vpn=true`、`bypass=true`，但不把它作为本机必需证据。
4. 检查 IPv4/IPv6 `zte_fw_gms` 没有有效 UID DROP。
5. Google Play 更新页、搜索和详情页已通过；下载/更新与后台恢复仍待单独授权测试。
6. 连续切换节点十次，30 秒宽限内不应出现瞬间断网。
7. VPN 保持超过 70 分钟，确认厂商旧的 15/30/60 分钟任务也不能重新写 DROP。

### C. 省电恢复

1. 关闭 VPN。
2. 30 秒宽限结束后，日志应重新进入原厂 `updateCheckStrategy()`。
3. 只有在 `latchsky_enable=1`、无 VPN、厂商判断 GMS 不可用时，原厂 Google 专项省电路径才会恢复。
4. 做至少 8–24 小时 A/B：同样的亮屏、Wi-Fi、同步和 VPN 时长，对比 GMS partial wakelock、CPU 时间、
   闹钟次数及待机掉电。短时间“感觉省电”不能作为通过证据。

当前开关为 `latchsky_enable=1`。已证明 VPN 活跃时不误封；VPN 关闭后的专项省电恢复和实际功耗仍未验收。

## 正常回滚

```sh
adb shell "su -c '/data/adb/lspd/cli modules disable io.github.dynamicfire.zte.gmsoptimizerguard'"
adb reboot
```

确认系统恢复后，可再卸载 APK。禁用与卸载都属于设备修改，执行前应记录当前状态并确认恢复路径。

## 异常启动回滚

- 若系统能启动且 ADB 可用，优先用上面的 Vector CLI 禁用，然后重启。
- 若 Vector/系统服务未能起来，CLI 可能因 daemon socket 不存在而不可用；不要把它当作 bootloop 的唯一救援。
- 强制进入 Android 安全模式后卸载这个第三方模块 APK，再正常重启。Vector 维护者也建议以系统安全模式或
  recovery 卸载有问题的 Xposed 模块。
- 如果安全模式不可进入，只能使用已准备好的 recovery/root 救援路径；在没有这条路径前，不应启用
  system_server 作用域模块。
