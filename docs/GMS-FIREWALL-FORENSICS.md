# W200DS `zte_fw_gms` 防火墙规则来源取证报告

调查日期：2026-08-31

调查范围：取证阶段只读检查实机运行态、系统属性、系统组件和固件反编译结果；当时未修改防火墙、系统设置、系统属性、应用状态或启动项。后续模块实现与实机验收单独记录在第 10 节。

## 结论

这组规则不是 Clash Meta 创建的，也不是节点失效，更不是 Android AOSP 的标准防火墙策略。

直接创建者是 W200DS 自带、运行在 `system_server` 内的 ZTE 厂商类：

```text
ZtePowerTrackerService
  -> new GoogleOptimizer(...)
  -> INetworkManagementService.setIptables("ztenet ...")
  -> ZTE 修改过的 NetworkManagementService
  -> 把原始防火墙命令塞进 netd 的 bandwidthRemoveInterfaceQuota(...)
  -> ZTE 修改过的 netd
  -> iptables-restore / ip6tables-restore
  -> OUTPUT 首位跳转 zte_fw_gms
  -> 按 Google Play / GMS 的真实 UID 直接 DROP
```

它属于 ZTE/Nubia 固件内置的 Google 服务“优化”策略。该策略会用 `InetAddress.isReachable(1500)` 之类的非 HTTP/TLS 可达性检查判断 Google 是否“可用”；判断失败后，它不是让请求继续交给 VPN，而是在内核 `OUTPUT` 链按应用 UID 封掉 Google Play 和 Google Play 服务的全部出站流量。这正好解释了“同一节点在电脑和其他手机有效、Clash 节点测试也可能成功，但本机 Google Play 仍无法联网”。

## 证据强度

### 已直接证明

1. 实机 IPv4/IPv6 的 `OUTPUT` 第一条规则均跳转到 `zte_fw_gms`。
2. 该链实际按 UID 丢弃 Google Play 服务和 Google Play 的包，计数器持续增长。
3. 实机 `services.jar` 中的 `GoogleOptimizer` 逐字生成相同的链名和规则。
4. `GoogleOptimizer` 由 `ZtePowerTrackerService` 在 `system_server` 启动时实例化。
5. ZTE 修改过的 `NetworkManagementService` 和 `netd` 构成命令执行通道；运行态 `iptables-restore`/`ip6tables-restore` 的父进程是 `netd`。
6. 关闭固件开发者选项里的“应用优化”会把 `Settings.Global.latchsky_enable` 设为 `0`；`GoogleOptimizer` 监听该键，并走清空 DROP、插入 `RETURN` 的内置放行路径。
7. 公开的 Nubia/RedMagic NX729J Android 13 固件含同源 `GoogleOptimizer`、同一链名和同一属性策略，说明它至少跨 W200DS 与 NX729J 两个独立产品族存在。

### 强推断，但不是厂商公开声明

- 类名、设置项、China 区域属性、百度/Google 对照探测以及 GMS 启停策略共同表明，其设计目的大概率是中国区固件里的省电、后台管控和 GMS 可用性策略。
- 没有证据证明厂商的主观目的就是阻止 Clash 或 VPN；但它客观上会破坏透明代理、TUN 和本地代理，这是代码行为可以直接推出的结果。

### 尚未唯一定位

- `GoogleOptimizer` 接收 `android.intent.action.GMS_AVAILABLE_STATE` 广播并据此更新 `mGmsAllowed`。在已扫描的系统 APK/JAR 中能确认多个接收者，但没有找到唯一、静态可见的发送者。发送动作可能由动态代码、混淆组件或其他厂商层发出。
- 这个未决点不影响规则来源归因：创建链、写入 UID DROP、执行命令、周期重建和内置关闭路径都已经从实机代码及运行态交叉证明。

## 1. 实机运行态：规则正在真实丢包

Google 相关 UID：

| 对象 | 包名 | 实机 UID |
|---|---|---:|
| Google Play | `com.android.vending` | `10205` |
| Google Play 服务 / GSF 共享 UID | `com.google.android.gms` 等 | `10235` |
| Clash Meta | Clash 包 | `10321` |

IPv4 关键结构：

```text
[1429453:2112514866] -A OUTPUT -j zte_fw_gms
[0:0]              -A zte_fw_gms -m string --string "services.googleapis.cn" --algo bm --to 65535 -j ACCEPT
[737:44235]        -A zte_fw_gms -m owner --uid-owner 10235 -j DROP
[1521:91244]       -A zte_fw_gms -m owner --uid-owner 10205 -j DROP
```

IPv6 同样存在：

```text
[281429:21073041] -A OUTPUT -j zte_fw_gms
[0:0]            -A zte_fw_gms -m string --string "services.googleapis.cn" --algo bm --to 65535 -j ACCEPT
[23:1855]        -A zte_fw_gms -m owner --uid-owner 10235 -j DROP
[3:240]          -A zte_fw_gms -m owner --uid-owner 10205 -j DROP
```

`zte_fw_gms` 位于 `OUTPUT` 的首位，早于 `oem_out`、`fw_OUTPUT`、`st_OUTPUT` 和 `bw_OUTPUT`。IPv4 首条跳转计数与下一条正常规则的计数差为 `2258`，恰好等于 `737 + 1521`，说明这 2258 个包就在 `zte_fw_gms` 内被终止，没有继续向后流动。

相邻采样中，IPv4 的 GMS DROP 从 `700` 增至 `737`，Play DROP 从 `1418` 增至 `1521`。报告完成前的最后一次只读复核又分别增至 `968` 和 `1952`，即在未改动规则的等待期间继续新增 `231` 和 `431` 次 DROP；`services.googleapis.cn` 例外规则仍为 `0` 次命中。因此它不是死规则或历史残留，而是仍在持续拦截。

## 2. 固件源码级归因：`GoogleOptimizer`

实机 `/system/framework/services.jar` 反编译后包含：

- `com.android.server.am.GoogleOptimizer`
- `com.android.server.am.ZtePowerTrackerService`
- `com.android.server.NetworkManagementService`

`ZtePowerTrackerService.onStart()` 中直接执行：

```java
this.mOptimizer = new GoogleOptimizer(mContext);
```

`GoogleOptimizer` 构造时创建链，并将它插到 `OUTPUT` 首位：

```text
ztenet -N zte_fw_gms
ztenet -I OUTPUT -j zte_fw_gms
```

当 `optimize(0)` 进入限制状态时，它会：

1. 清空 `zte_fw_gms`；
2. 按当前用户查询 `com.google.android.gms` 的真实 `ApplicationInfo.uid`；
3. 追加 `-m owner --uid-owner <GMS_UID> -j DROP`；
4. 查询 `com.android.vending` 的真实 UID；
5. 追加对应 UID DROP；
6. 尝试加入两个基于报文字符串的例外规则。

当 `optimize(1)` 进入放行状态时，它会：

```text
-F zte_fw_gms
-I zte_fw_gms -j RETURN
```

因此最关键的事实不是“某个固定 UID 被误写”，而是厂商代码会查询 Google 应用的实际 UID，再主动重建 DROP。卸载重装、UID 改变或多用户场景并不能从根本上绕开。

## 3. 它为何会误伤 Clash/VPN

限制逻辑同时依赖：

- `mGmsAllowed == false`；
- `latchsky_enable != 0`，该设置不存在时默认值就是 `1`；
- 当前屏幕、Wi-Fi、移动数据和 VPN 状态；
- Google 可达性检查结果。

可达性线程先解析 `www.baidu.com`，再解析 `www.google.com`，并对地址调用 Java `InetAddress.isReachable(1500)`。这不是一次 Google HTTPS 请求，也不等价于 Clash 节点或 TUN 的 TCP/TLS 能力。ICMP、底层探测、DNS 返回路径和 VPN 接管路径中任一环节不同，都可能让它把一个实际可用的代理环境判断为“Google 不可达”。

判断不可达后执行 `optimize(0)`；随后 Google Play/GMS 发出的包在内核 `OUTPUT` 最前面按原始应用 UID 被 DROP，无法正常进入 Clash 的后续转发路径。于是形成自强化循环：

```text
探测认为 Google 不可达
  -> 按 Google UID 封网
  -> Google 应用确实无法通过 VPN 验证可达
  -> 后续屏幕/网络变化或周期检查继续维持、重建限制
```

周期在代码中为：亮屏 Wi-Fi 15 分钟、亮屏移动网络 30 分钟、熄屏 60 分钟；网络和屏幕状态变化也会重新评估。因此手工只清空一次 iptables 链不是持久修复。

## 4. 两个明显实现缺陷

### 4.1 白名单本身存在拼接错误

代码尝试添加：

```text
--string checkin.gstatic.com--algo bm
```

`checkin.gstatic.com` 与 `--algo` 之间缺少空格。实机运行态也确实没有这条规则，符合命令执行失败的结果。

另一条 `services.googleapis.cn` 例外规则存在，但 IPv4/IPv6 计数均为 `0`。它依赖 xtables 在单个包负载中做字符串匹配，本来就无法可靠表达“允许这个 HTTPS 服务”。

### 4.2 错误被隐藏

规则写入路径存在空的 `RemoteException` 捕获；`NetworkManagementService` 也只是记录宽泛异常。加上模糊的 UI 名称“应用优化”，用户很难从界面或普通日志知道 Google 应用正在被内核防火墙封锁。

## 5. `ztenet`：伪装在标准 netd 接口里的原始命令通道

ZTE 版 `NetworkManagementService` 定义：

```text
HS_NET_CMD_PREFIX = "ztenet"
HS_NET_CMD_KEY    = "zte_fw_"
```

它验证调用方具备 `android.permission.ZTE_MANAGE_NETWORK_POLICY` 后，把 `ztenet ` 后面的字符串传给：

```java
mNetdService.bandwidthRemoveInterfaceQuota(cmd.substring(7));
```

在官方 AOSP 中，[`bandwidthRemoveInterfaceQuota(ifName)`](https://android.googlesource.com/platform/system/netd/+/003524e75b847376b361f310443405bdb6d37c6d/server/binder/android/net/INetd.aidl) 的参数是接口名，用途是移除带宽配额，不是执行任意防火墙文本。ZTE 修改了服务端语义，把这个 Binder 方法复用成隐藏的 `ztenet` 防火墙命令入口。

实机同时观察到：

- `netd` 二进制含 `zte_fw_*`、`runIptablesCmd()` 和 `iptables-restore` 相关字符串；
- `iptables-restore` 与 `ip6tables-restore` 的父进程均为 `netd`。

所以职责可明确分开：`GoogleOptimizer` 决定封谁并生成规则，ZTE 版 `NetworkManagementService/netd` 执行规则。

## 6. 为什么不是 `sprd_networkcontrol`

实机 `/system_ext/bin/sprd_networkcontrol` 是 root/SELinux 域 `u:r:sprd_networkcontrol:s0` 的展锐网络控制组件，但：

- 二进制字符串中没有 `zte_fw_gms`；
- 没有 Google 包名、`uid-owner` 或对应 DROP 模板；
- 没有观察到它作为 `iptables-restore` 的父进程；
- 公开的其他 Unisoc Android 13 固件也包含该通用组件；
- 同一套 `GoogleOptimizer` 和 `zte_fw_gms` 逻辑还出现在高通平台的 Nubia/RedMagic NX729J 固件中。

因此 `sprd_networkcontrol` 最多是平台通用网络组件，不是这套“封 Google UID”策略的作者。跨高通与紫光展锐产品族的同源实现，反而把来源锁定到了 ZTE/Nubia 的 framework/MyOS 公共层。

## 7. 厂商属性与中国区策略

实机 `/vendor/build.prop` 明确包含：

```text
ro.vendor.feature.zte_strategy_disable_gms=true
```

同时当前区域为 China，并存在 GMS freezer、GMS 自动启动白名单等 ZTE 特性开关。`framework.jar` 的 `FeatureImplProperty` 会把 `ZTE_STRATEGY_DISABLE_GMS` 转为小写并加上 `ro.vendor.feature.` 前缀，因此 `AutoLaunchManagerService` 读取的正是上述属性。

需要准确区分：

- 这个属性直接控制的是更广泛的 GMS 包启停/自启动策略；
- 直接创建 `zte_fw_gms` 和 UID DROP 的代码仍然是 `GoogleOptimizer`；
- 该属性不是目前已证明的 `GoogleOptimizer` 唯一开关，不能简单修改它就假定问题已经解决。

公开 NX729J 固件的固定提交也包含相同策略：

- [`services.jar`](https://gitlab.com/Alejandroprz3095/NX729J/-/blob/a03ae24961775a651b00dff5db0672941ab19fb0/system/system/framework/services.jar)
- [`framework.jar`](https://gitlab.com/Alejandroprz3095/NX729J/-/blob/a03ae24961775a651b00dff5db0672941ab19fb0/system/system/framework/framework.jar)
- [`vendor/build.prop`](https://gitlab.com/Alejandroprz3095/NX729J/-/blob/a03ae24961775a651b00dff5db0672941ab19fb0/vendor/build.prop)

[W200DS 官方资料](https://www.zte.com.cn/china/product_index/secure_office_cloudcomputers/usmart/w200ds/W200DS.html)列出 Android 13 / T760；[RedMagic 8 Pro 官方规格](https://eu.redmagic.gg/pages/redmagic-8-pro-specs)列出 Snapdragon 8 Gen 2。只能严谨地说该实现至少跨两个产品族存在，不能据此扩大成“所有 ZTE 设备都有”。

## 8. 与 AOSP 的差异

Android 官方 [`FirewallController`](https://android.googlesource.com/platform/system/netd/+/33fe73272684d9be6c124d67662e04d16045f8ff/server/FirewallController.cpp) 使用 `fw_INPUT`、`fw_OUTPUT`、`fw_dozable`、`fw_standby`、`fw_powersave` 等标准链和 UID 规则框架。官方 [Android 13 services/server 源码树](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/) 中没有 `GoogleOptimizer`、`ZtePowerTrackerService`、`zte_fw_gms`、`ZTE_STRATEGY_DISABLE_GMS`，也没有专门按 GMS/Play UID DROP 的对应逻辑。

所以它不是 Android 原生的“正常防火墙行为”，而是明确的厂商增补。

## 9. 为什么说这个设计确实很糟糕

技术层面的问题不是单一 bug，而是几项设计叠加：

1. 把“Google 探测失败”转换成“主动封死 Google 应用全部出站流量”，因果方向错误。
2. 使用不等价于 HTTPS/代理可用性的 `isReachable()` 作为决策依据，对 VPN/TUN 天生不友好。
3. 在 `OUTPUT` 首位按真实 UID DROP，覆盖所有目的地址和协议，绕开普通应用级代理策略。
4. 把原始防火墙文本藏进名为 `bandwidthRemoveInterfaceQuota` 的标准 Binder API，维护性和可审计性都很差。
5. 白名单使用脆弱的报文字符串匹配，其中一条还有确定的命令拼接错误。
6. 异常处理会吞掉关键失败信息。
7. 面向用户的关闭项只叫“应用优化”，没有提示它会改变 Google 应用的内核网络权限。
8. 规则会在 `system_server` 启动和网络/屏幕状态变化后重新评估，手工清链容易被恢复。

## 10. 修复路径与当前模块

取证阶段首先验证了开发者选项中的“应用优化”会把 `latchsky_enable` 设为 `0`，并触发 ROM
自己的 `optimize(1)` 放行路径。这仍是最简单、可逆、风险最低的止血办法，但会整体关闭这组
Google 专项策略，不能同时保留其可能带来的后台节能。

随后实现并实机验证了仓库中的
[GMS Optimizer Guard](../xposed/zte_gms_optimizer_guard/README.md)：

- 只对精确 P720P01 Android 13 fingerprint 和已审计的 `GoogleOptimizer` 结构挂钩；
- Android VPN 活跃及断开后的 30 秒宽限内，取消残留可达性任务并把在途 `optimize(0)`
  转换为厂商自己的清理/放行路径；
- VPN 长期关闭后重新进入原厂 `updateCheckStrategy()`，而不是永久关闭整套策略；
- 普通 Doze、App Standby 和中兴对其他应用的后台管理保持不变；
- callback 或策略检查异常时 fail-open，优先避免重新封死 Google Play。

2026-08-31 的当前实机候选已通过安全加载、15 分钟任务窗口、Play/GMS UID HTTP 204，
以及 Google Play 更新页、搜索和详情页。真实下载/更新、VPN 断开恢复、70 分钟守护和
8–24 小时耗电 A/B 尚未完成，不能据此宣称已有量化省电收益。

`persist.sys.optimizer.disable=true` 仍是代码里的另一开关，但只在 `GoogleOptimizer` 构造时读取，
通常需要重启 `system_server` 或设备才生效，且比内置 UI 开关范围更粗。仍不建议用
`iptables -F`、删除整个 `OUTPUT` 链或全表清空作为修复：这会影响 Android 其他安全/计费规则，
而且厂商服务可以重新写回。

## 11. 取证件校验值

| 实机取证件 | SHA-256 |
|---|---|
| `services.jar` | `1ba7d8f240fb267a0213bdf0b0801cec162cb188674a915a2e1a4e050d354237` |
| `framework.jar` | `67aa2007263c305d3addaf3cf33c45b48e6e379f75469b40c9430c768686a33e` |
| `Settings_MFV_tablet.apk` | `c031c6f3659b4800bd923fc670eef70223d4cc34f70d52433cbcb4a5f9770dec` |
| `StrategyProvider_mfv.apk` | `9bbceab5a5eb3b6bcf3739b26e446145b0d9157552a6c70a6053ee09d7c7083c` |
| `ZteCloudUpdateProvider_mfv.apk` | `ca5b423854a14d3cf63362e3476ac7411a64067f90169e3001d3ba73fd60a8ee` |

这些哈希用于确认本报告分析的二进制与本次从实机提取的取证件一致。
