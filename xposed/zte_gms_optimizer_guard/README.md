# 中兴 GMS 优化守卫

这是针对 ZTE W200DS（系统内部型号 `P720P01`）当前 Android 13 精确固件的
Vector/Xposed 模块。它修补
`com.android.server.am.GoogleOptimizer` 的策略竞态，不直接修改 `services.jar`，也不运行
常驻 iptables 轮询脚本。

## 策略

- Clash/VPN 活跃：取消厂商待执行的 Google 可达性任务，把任何 `optimize(0)`（封锁）转换为
  厂商自己的 `optimize(1)`（清理/放行），并让独立 GMS 唤醒锁策略使用可用档位。
- VPN 刚断开：保留 30 秒宽限，避免节点切换时短暂重新封锁。
- VPN 关闭超过宽限：重新进入厂商原有 Google 专用省电路径。
- “应用优化”关闭、厂商已判定 GMS 可用或内部检查异常：始终放行，阻止残留延时任务回写 DROP。
- OTA/固件指纹不一致：模块完全不挂钩，避免把旧补丁套到未知代码上。
- VPN 监听回调无法建立：有限重试三次；仍失败则本次开机保持放行，不拿 Google Play 可用性冒险。

本版审计基线为 `services.jar` SHA-256
`1ba7d8f240fb267a0213bdf0b0801cec162cb188674a915a2e1a4e050d354237`。运行时以精确固件指纹和
类/字段/方法结构双重校验；APK 不会在 system_server 启动路径同步散列整个 JAR。

普通 Android Doze、应用待机、中兴对其他应用的后台控制都不受影响。VPN 活跃时，模块只撤销
最容易破坏正常使用的 Google 专用网络封锁、特殊闹钟对齐/冻结状态；系统其余省电机制继续工作。

这里存在不可消除的取舍：如果 VPN 24 小时常驻，Google 专用的这组激进限制也会 24 小时暂停；
不能在同一时刻既冻结/封锁 GMS，又要求 Google Play 完全正常。这个模块保留的是“VPN 不用时恢复
原厂专项省电，VPN 使用时优先功能”，不是凭空创造无代价省电。

规则来源、厂商实现和原故障链见
[../../docs/GMS-FIREWALL-FORENSICS.md](../../docs/GMS-FIREWALL-FORENSICS.md)。

## 构建

项目包含最小的、仅用于编译的 legacy Xposed API stub；stub 通过 `compileOnly` 引用，不能被打进
APK。实际运行时由 Vector 提供 Xposed API。需要 JDK 17 或 21、Gradle 8.9、Android SDK 33；
首次构建若本机尚未缓存 Android Gradle Plugin 8.7.3 和 JUnit 4.13.2，需要先从官方 Maven 仓库解析依赖。

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"  # macOS 示例；Linux 按实际路径设置
cd xposed/zte_gms_optimizer_guard
gradle clean testDebugUnitTest lintRelease assembleRelease
./tools/verify-release.sh app/build/outputs/apk/release/app-release.apk
```

需要验证覆盖升级的签名连续性时，可把已安装 APK 的证书 SHA-256 通过
`EXPECTED_CERT_SHA256` 传给 verifier；不设置时只验证签名结构有效，不假定某个公开证书。

依赖已经完整缓存时，可以给 Gradle 加 `--offline`。实机候选包使用 `release` 构建以获得单 DEX、
非 debuggable APK，但 v0.1.0 当前仍使用本机构建环境的 Android debug key 签名，只适合本地侧载；
后续覆盖升级必须继续使用同一签名。签名密钥和 APK 构建产物均不进入仓库。

## 安装边界

构建 APK 不会改变平板。安装、在 Vector 中启用、把作用域设为 `system/0`，以及重启平板，
必须作为单独的实机步骤执行。未启用模块时没有任何运行时效果。

详见 [INSTALL.md](INSTALL.md)、[TECHNICAL.md](TECHNICAL.md) 与
[防火墙来源取证](../../docs/GMS-FIREWALL-FORENSICS.md)。
