# Changelog

## Unreleased

### Clash Meta VPN Watchdog 1.0.2

- 加入独立的 `clash_meta_watchdog` APatch 模块源码、构建脚本和 W200DS 使用文档。
- 形成“最近任务锁定 + `used_module=6` 精确窄白名单 + Android 始终开启 VPN + APatch 守护”四层方案；守护只在 Clash 运行标记存在且包未被 Force stop 时恢复 `TunService`。
- 记录当前固件的双重清理链，并补充 `used_module=6` 精确包名白名单的手工配置、重启加载和按 `_id` 精确回滚流程，使正常上滑只移除任务卡片而不杀 VPN 进程。
- 尊重 Clash 内正常 Stop、系统强行停止和模块 Action 暂停；限制重试频率并对私有事件日志做大小上限。
- 明确恢复需要数秒并可能重置现有连接；设备诊断和验证只使用有线 USB ADB。

## 1.8.0 - 2026-08-29

- 将 HOME 固化从开机广播线程移到最早约 10 秒后的私有单次 AlarmManager finalizer，避开厂商更晚的异步覆盖。
- 即使开机广播最初看到稳定 MiFavor，也安排一次 guarded verification，以捕获之后出现的 MiFavor/USmart 半状态。
- finalizer 绑定当前 `BOOT_COUNT` 会话和持久 token；F9 选择器在显示前同步作废会话并取消 PendingIntent。
- 只有连续两次确认 `secure default_home=MiFavor`、实际 HOME resolver=USmart 时才调用 `switch_pad`，稳定 MiFavor、主动 USmart、未知或第三方 HOME 都安全退出。
- 增加 `MAIN/HOME` 包可见性；仍只有 `RECEIVE_BOOT_COMPLETED` 一个 Android 权限。

## 1.7.0 - 2026-08-29

- 将 `switch_pad` 固化延迟调整为 3 秒，避开 `switch_mode` 的异步 HOME 写入覆盖。
- 为开机恢复增加最小诊断日志，便于确认分支与调用顺序。

## 1.6.0 - 2026-08-29

- 修复云状态重启后的 HOME 半一致状态：`switch_mode` 只负责一次性拉起 MiFavor，随后由单向 `switch_pad` 固化系统 HOME 角色。
- 增加 HOME 键回归，确保开机恢复后按 HOME 不会重新进入 USmart。

## 1.5.0 - 2026-08-29

- 将默认平板启动、F9 自定义选择和云状态重启恢复整理为一套可重建方案。
- 开机接收器只在持久化 HOME 明确为 USmart 时调用原厂 `switch_mode`。
- 非幂等的 `switch_mode` 每次开机最多调用一次，避免状态写入延迟导致二次 toggle。
- 取消或启动失败时通过原厂 `switch_pad` 返回平板模式。
- 真实选择器 Activity 改为 `exported=false` 和 `noHistory=true`。
- 保留厂商所需的公开固定 alias，但移除普通桌面图标入口。
- 用 `<queries>` 代替 `QUERY_ALL_PACKAGES`；唯一 Android 权限为 `RECEIVE_BOOT_COMPLETED`。
- 加入 APatch APM 1.3.0 源文件和打包脚本。
- 加入 Ubuntu 可移植构建、SHA-256、固定签名参数、安装、回滚和真机测试文档。

## 1.4.0 - 2026-08-29

- 增加可编辑的任意 LAUNCHER 应用列表和中文恢复错误提示。
- 增加 `BOOT_COMPLETED` 云状态恢复。
- 恢复并保留原厂 F9、HOME 和输入映射链路。
- 在 W200DS 上完成平板态、云状态重启和 F9 往返验证。

## 初始版本

- 利用 `pc_switch_mode=0` 和 `com.zte.mobile` 固定空槽提供 F9 应用选择器。
- 记录 W200DS F9 keycode、输入映射与云电脑包映射的初步观察。
