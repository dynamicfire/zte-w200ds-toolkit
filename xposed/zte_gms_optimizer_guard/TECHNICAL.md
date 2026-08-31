# 技术设计

## 针对的厂商缺陷

`GoogleOptimizer` 把四件事绑在同一个 `optimize(int)` 中：GMS/Play UID 防火墙、GMS 特殊闹钟对齐、
可能的 CPU freezer，以及 GMS 可用状态。原实现还存在延迟消息未取消、延迟任务执行前不重查开关、
以及在 Android VPN 存在时仍用错误可达性结果决定封锁等问题。

## 挂钩点

- `updateCheckStrategy()`：低频刷新设置/VPN状态；bypass 时跳过原方法并异步 clear。
- `handleOptimizerMsg()`：阻止旧的 15/30/60 分钟任务继续探测和封锁。
- `optimize(0)`：只读缓存的最终保险；跳过 DROP，并在 OEM Handler 上执行原始 `optimize(1)`。
- `getGmsAllowStatus()`：PowerManager 敏感路径只读 volatile 快照，不做 Binder 或网络枚举。
- `GoogleOptimizer(Context)`：捕获唯一实例、做初始快照、注册 VPN callback。

所有 netd/freezer/alarm 清理都串行到 `GoogleOptimizer.mHandler`，不与设置观察者或 callback 并发修改
厂商的静态 `ArrayList` 和 iptables 状态。调用 clear 使用 `XposedBridge.invokeOriginalMethod()`，避免
再次进入本模块自己的 optimize hook。

## 状态机

```text
VPN active / 30s grace / latchsky off / optimizer disabled / GMS already allowed / inspection failure
    -> bypass
    -> cancel message 1
    -> original optimize(1)
    -> getGmsAllowStatus = true

VPN absent beyond grace + latchsky on + callback healthy
    -> call original updateCheckStrategy()
    -> OEM decides when and whether to schedule its power policy
```

VPN callback 使用 `clearCapabilities()` 后只请求 `TRANSPORT_VPN`，因此不会把默认 `NOT_VPN` 与 VPN
transport 组合成不可能条件，也不要求 `VALIDATED`。任何 Android VpnService（不只 Clash）都会暂停
这组 Google 专项优化；root TUN/TProxy 若未注册为 Android VPN 则不在当前检测范围，必须实机确认。

## 固件门与失败策略

- UID 必须为 1000；作用域必须是 `system/0`。
- SDK、build fingerprint、incremental 必须精确匹配。
- 构造函数、四个方法、四个字段的类型必须匹配审计结果。
- hook 安装中途失败会逆序 unhook；若某个 unhook 自身失败，本次开机禁止重复安装。
- VPN callback 有三次有限重试；持续失败时保持 fail-open 到重启。

审计基线 `services.jar` SHA-256：

```text
1ba7d8f240fb267a0213bdf0b0801cec162cb188674a915a2e1a4e050d354237
```

为避免在 system_server 启动关键路径同步读取并散列大 JAR，运行时不执行整包 SHA-256；因此“保留原
指纹但人工替换 services.jar”仍属于未覆盖边界。正常 OTA 会被 fingerprint/incremental 门挡住。

## 省电边界

VPN 活跃时会撤销 Google 专用网络 DROP、特殊闹钟对齐和可能的 GMS freezer，以保证 Play/GMS 正常。
普通 Doze、App Standby、中兴对其他应用的后台策略及 `PM_GMS_VERSION=2` 的较宽松限制仍存在。
VPN 关闭超过宽限后，模块重新调用原厂决策入口，所以原厂专项省电可以恢复。
