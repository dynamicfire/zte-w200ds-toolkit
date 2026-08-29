# W200DS 固件适配记录

这份文档记录本项目在中兴 W200DS Android 13 固件上的观察、状态转换和最终设计。它不是其他中兴设备的通用规格。

## 已确认的固件链路

| 项目 | 当前固件观察值 |
|---|---|
| F9 Android keycode | `307` / `LAUNCHER_SWITCH` |
| 观察到的键盘扫描码 | `250` |
| 平板 HOME | `com.zte.mifavor.launcher/com.android.launcher3.uioverrides.QuickstepLauncher` |
| 云模式 HOME | `com.zte.usmartlauncher/.activity.UsmartMainActivity` |
| 模式 Provider | `content://com.zte.usmartlauncher.defaulthome` |
| 强制回平板方法 | `switch_pad` |
| 双向完整切换方法 | `switch_mode` |
| 自定义槽设置 | `Settings.System pc_switch_mode=0` |
| 自定义槽组件 | `com.zte.mobile/com.zte.mspice.ui.WelcomeActivity` |
| 开机选择属性 | `ro.vendor.feature.zte_feature_show_smart_launcher_when_boot` |

`pc_switch_mode` 的其他数值由运营商和固件版本决定。旧版仓库观察到过 `4`，本次测试设备安装前记录为 `1`；公开安装和回滚流程因此只要求保存本机原值，不硬编码某个数字。

## F9 状态转换

平板状态短按 F9 时，固件先处理输入模式，然后切换逻辑 HOME。`pc_switch_mode=0` 会让 USmart 显式启动固定的 `com.zte.mobile` 组件；选择器本身不需要捕获按键。

```text
MiFavor / 触屏 DIRECT
        |
        | F9 keycode 307
        v
USmart 云状态 / 厂商输入映射
        |
        | 显式启动固定 alias
        v
F9 应用选择器 -> 用户选择的远控或云电脑 App
        |
        | 再按 F9
        v
MiFavor / 触屏 DIRECT
```

这也是为什么普通按键映射应用不是可靠方案：F9 已在系统策略层被拦截，不会作为普通按键继续分发给前台 App。

## 为什么不能停用 USmart Activity

早期为了绕过开机模式选择页，曾停用：

```text
com.zte.usmartlauncher/.activity.UsmartMainActivity
```

这样确实可以阻止选择页出现，但也破坏了 F9 返回平板的原厂链路。最终方案必须保持该 Activity 启用，只通过属性抑制“开机强制显示选择页”：

```properties
ro.vendor.feature.zte_feature_show_smart_launcher_when_boot=false
```

## 为什么 APM 只有 system.prop

当前 APatch 环境实测没有自动执行尝试过的模块启动脚本阶段，因此最终模块没有保留 `service.sh` 或 `boot-completed.sh`。模块只做一件确定的事：通过 `system.prop` 覆盖开机属性。

这也避免了 Root 启动脚本持续轮询或反复改写 HOME。云状态重启恢复由普通 APK 的标准 `BOOT_COMPLETED` 广播处理。

## 开机恢复为什么同时使用 switch_mode 与 guarded switch_pad

交互状态下的“取消选择 / 返回平板模式”使用 `switch_pad`，它是单向返回接口。

但在“设备于云状态关机后重启”的真实测试中，单独调用 `switch_pad` 可以改变逻辑 HOME，却可能让旧 USmart Activity 继续停在可见前台。完整 `switch_mode` 会沿厂商路径恢复状态并启动 MiFavor。

由于 `switch_mode` 是双向 toggle，而固件又会在广播之后继续异步写 HOME，接收器遵守这些约束：

1. 只有 `Settings.Secure default_home` 明确等于 `com.zte.usmartlauncher` 才调用。
2. 每次开机最多调用一次；结果不明确时也不盲目重放。
3. 只要开机时识别到明确的 USmart 或 MiFavor 状态，就安排一次最早约 10 秒后的私有验证；因为接收器刚运行时看似稳定的 MiFavor/MiFavor，之后仍可能被固件覆盖成半状态。
4. finalizer 必须属于当前 `BOOT_COUNT` 会话、持久 token 仍有效，且未被 F9 选择器取消。
5. 调用 `switch_pad` 前连续两次确认：`secure default_home` 是 MiFavor，但 `MAIN/HOME` resolver 仍是 USmart。任何未知、稳定 MiFavor、主动 USmart 或第三方 HOME 都安全退出。

真机发现只调用 `switch_mode` 可能出现“可见前台和 `secure default_home` 已是 MiFavor，但系统默认 Launcher 仍是 USmart”的半状态，按 HOME 会重新回云模式。把 `switch_pad` 放在同一个开机广播线程里仍可能被厂商更晚的异步写入覆盖，因此最终使用 `AlarmManager` 延迟 finalizer。

对当前 USmartLauncher 的反汇编显示，`switch_pad` 分支只替换 preferred HOME 并写 `default_home`，没有 `startActivity`；真机把“设置”留在前台调用后，前台也保持不变。因此即使验证 Alarm 被系统延后，它也不会把正在使用的普通 App 拉回桌面。F9 选择器还会在 `onCreate` 入口发布进程内取消信号、同步作废当前开机会话并取消 PendingIntent，避免主动云模式被旧任务覆盖。普通 `AlarmManager.set()` 不要求精确闹钟权限，但“10 秒”只是最早执行时间。

## 组件导出边界

- `PcChooserActivity`：`exported=false`，外部不能直接指定真实实现类。
- `com.zte.mspice.ui.WelcomeActivity` alias：`exported=true`，因为原厂 USmart 必须显式启动它。
- `TabletBootReceiver`：`exported=false`；仍可接收系统保护的 `BOOT_COMPLETED`。
- alias 没有桌面 `MAIN/LAUNCHER` 过滤器，因此不会出现一个容易被误开的普通图标。
- Activity 使用 `noHistory=true`，离开选择器后不残留隐藏任务。

## 包可见性

选择器只需要：

- 查询带 `ACTION_MAIN + CATEGORY_LAUNCHER` 的 Activity；
- 查询当前 `ACTION_MAIN + CATEGORY_HOME` resolver；
- 访问中兴模式 Provider。

Manifest 用 `<queries>` 声明这些可见对象，不申请 `QUERY_ALL_PACKAGES`。因此 APK 的唯一权限是 `RECEIVE_BOOT_COMPLETED`。

## 输入映射验收

屏幕亮起、处于平板桌面且原厂键盘输入设备已枚举时，实测：

```text
himax-touchscreen: Touch Input Mapper (mode - DIRECT)
ztp_input:          Touch Input Mapper (mode - POINTER)
```

熄屏时触屏 mapper 显示 `DISABLED` 是正常的省电暂停，不能据此判断模式切换失败。必须先确认设备 `Awake/interactive` 再读映射。

若 `dumpsys input` 中根本没有 `ztp_input`，说明当次会话没有枚举到键盘设备，不能把“未看到 POINTER”判成模式映射失败；应先重新连接实体键盘再验收。

## 保持不变的厂商行为

- F9 短按仍由原厂策略层接管。
- F9 长按及防抖仍由固件决定。
- 观察到固件约有 1000 ms 的重复按键防抖；自动化往返测试应间隔至少 1.5 秒。
- 本项目不修改 keylayout、framework、vendor APK、SELinux 或输入驱动。

## OTA 后应重新确认

1. 属性名和值是否仍存在。
2. USmart Activity、Provider authority 和两个方法是否仍可用。
3. `pc_switch_mode=0` 是否仍指向相同固定组件。
4. HOME 包名是否未变化。
5. 实体 F9、触控、鼠标、右键、滚轮和键盘行为。
6. 选择器的开机广播是否仍会被系统投递。
