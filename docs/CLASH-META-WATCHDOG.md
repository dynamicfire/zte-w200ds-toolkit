# Clash Meta VPN 守护模块（W200DS）

`clash_meta_watchdog` 是针对中兴 W200DS 最近任务清理行为的可选 APatch 模块。它不修改 Clash Meta APK、订阅、配置文件、数据库、系统分区或 SELinux，只在系统意外杀死 VPN 服务后尝试恢复。

当前版本：**1.0.2**（versionCode 3）。只在 W200DS / Android 13 与 Clash Meta `2.11.33.Meta`（包名 `com.github.metacubex.clash.meta`）的当前组合上完成验证。

## 四层配合

1. **中兴最近任务锁定是前置条件。** 在 Clash 最近任务卡片的菜单中选择“锁定应用”。这会保护厂商的第一条 `noteRemoveTask` 清理链，避免上滑把包设为 `stopped=true` 的 Force stop。
2. **给 Clash 加入厂商的“仅移除任务”窄白名单。** 当前 W200DS 固件的 Launcher 还会走第二条 `startSingleAppCleanupFromRecent` 清理链；即使卡片已锁定，它仍会向主进程和 VPN 进程发送 `SIGKILL`。`special_class_list` 中 `used_module=6` 的精确包名条目会让这条清理链提前返回。这个条目只影响 Clash，不会全局关闭最近任务清理。
3. **Android“始终开启 VPN”作为系统层保护。** 在系统 VPN 设置中为 Clash 开启“始终开启 VPN”，但不要启用“阻止不使用 VPN 的连接”。它保留 Android 的 VPN 所有权和开机语义；当前中兴固件上它本身不足以保证进程被杀后的自动重启。
4. **APatch 守护负责最后兜底。** 守护只读取 Clash 私有目录中的零字节运行意图标记 `files/service_running.lock`。只有标记存在、后台 `TunService` 不健康、且 PackageManager 没有显示 `stopped=true` 时，才通过 Clash 导出的 `ExternalControlActivity` 与 `START_CLASH` action 请求恢复。

这四个层次不能互相替代。最近任务锁定保护第一条清理链，窄白名单保护第二条清理链，Always-on 保留系统 VPN 语义，守护只处理仍然发生的意外死亡。守护不会盲目保持进程常驻：Clash 内正常 Stop 会移除运行标记，系统设置中的“强行停止”会设置 stopped 状态，两者都会被尊重。

## 可见效果与边界

锁定后的 Clash 卡片仍可从最近任务中上滑移除。窄白名单生效时，卡片消失，VPN `:background` 进程 PID 与前台 `TunService` 应保持不变，连接不需要重建。主进程 PID 可作为短观察窗口内的辅助证据，但 Android 仍可能在之后回收空闲主进程，不应把它的长期 PID 不变当作唯一标准。若 OTA、策略数据库重建或其他清理路径仍终止 VPN 进程，守护以 5 秒间隔检查，并在 3 秒防抖后调用 Clash 的导出入口；正常情况下会在随后数秒恢复。兜底恢复无法保留旧 TCP/UDP 会话。

模块最多在 10 分钟内尝试 3 次；连续失败后冷却 15 分钟，防止错误版本或损坏配置造成重启循环。私有事件日志限制在约 64 KiB，不记录订阅、节点、URL 或流量内容。

### 本次真机验收

2026-08-29 在上述组合上完成的项目如下：

| 验收项 | 结果 |
| --- | --- |
| 只开启中兴最近任务锁定 | 不足；卡片上滑后主进程和 `:background` 仍被 `SIGKILL` |
| 插入精确窄白名单并重启后实际上滑 | 通过；卡片消失，两个 PID 在 15 秒观察窗口内不变，`TunService` 保持前台，IP 与 DNS 联网成功，无守护恢复事件 |
| 人为终止 `:background` 进程的守护兜底 | 通过；新 PID 出现，日志为 `unexpected_service_loss` → `restart_attempt` → `restart_succeeded` |
| Clash 内正常 Stop | 通过；标记消失，等待期内未被重启 |
| Android 应用信息的“强行停止” | 通过；包为 `stopped=true`，记录 `explicit_force_stop` 且未重启 |
| VPN 原本运行时重启设备 | 通过；解锁后守护与 VPN 正常运行 |
| VPN 原本停止时重启设备 | 尚未单独进行完整重启验收 |

## 构建与安装

在仓库根目录构建：

```bash
./scripts/build-clash-watchdog-apm.sh
```

输出为：

```text
build/outputs/clash-meta-vpn-watchdog-w200ds-v1.0.2.zip
build/outputs/clash-meta-vpn-watchdog-w200ds-v1.0.2.zip.sha256
```

先完成最近任务锁定。随后在设备已解锁时用有线 ADB 查询并添加窄白名单；查询必须先于插入，避免重复行。下面的第一条命令在设备上查询、在 Linux 电脑上精确过滤结果：

```bash
adb shell su 1000 -c 'content query --uri content://com.zte.heartyservice.strategy.provider/special_class_list' \
  | grep -F 'class_name=com.github.metacubex.clash.meta, type=1, used_module=6, compare_mode=1'

adb shell su 1000 -c 'content insert --uri content://com.zte.heartyservice.strategy.provider/special_class_list --bind class_name:s:com.github.metacubex.clash.meta --bind type:i:1 --bind used_module:i:6 --bind compare_mode:i:1'
```

如果第一条命令已返回匹配行，就不得再执行插入；这个既有行不属于本次配置，后续卸载时也不得删除。只在查询无结果时执行插入；插入后立刻重复查询并记录系统分配的 `_id`，回滚时只删除本次新建行。这个 Provider 不发送缓存变更通知；不要尝试杀死策略 APK 或伪造 Binder 调用，必须正常重启一次才可可靠加载到 `system_server`。

固件 OTA 或厂商策略库升级可能重建这张表。升级后要先重新查询精确条目，再进行上滑测试；不要假定旧 `_id` 仍然有效。

再在系统 VPN 设置中开启“始终开启 VPN”（保持 lockdown 关闭），从 APatch 管理器安装 ZIP 并重启。重启会同时加载窄白名单并由 `boot-completed.sh` 启动守护；也可以在 APatch 的模块页面按一次 Action 立即启动守护，但 Action 不能代替白名单所需的重启。守护运行后再次按 Action 会暂停或恢复守护，但不会停止当前 VPN。

本项目的设备诊断和命令验证只使用**有线 USB ADB**，不要为了安装或测试打开无线 ADB。若立即启动脚本，应先确认 APatch 将模块放在 `modules` 还是 `modules_update`，再使用实际路径；不要猜路径。

## 验证清单

1. 启动 Clash 并确认 VPN 正常联网。
2. 记录 Clash 主进程和 `:background` PID，从最近任务上滑 Clash 卡片；卡片应消失，立即及短观察窗口内 `:background` PID、前台 `TunService` 与 VPN 联网应保持不变；主进程 PID 仅作辅助证据。
3. 若 PID 仍被固件改变，查看 `/data/adb/clash_meta_watchdog/events.log`；一次兜底恢复应出现 `unexpected_service_loss`、`restart_attempt`、`restart_succeeded`，不应重复循环。
4. 在 Clash 内按正常 Stop，等待至少 30 秒；VPN 应保持停止，日志进入 `vpn_not_requested`。
5. 重新启动 Clash，再从 Android 应用信息中“强行停止”；守护应记录 `explicit_force_stop` 并保持停止。
6. 重启各测试一次：Clash 原本运行时应恢复，原本停止时不应被守护擅自启动。

设备级检查必须先精确确认目标包名、进程 PID 和模块路径。不要把 PID、模块阶段目录或其他设备上的固件行为写死在自动化脚本里。

## 隐私与权限边界

守护读取的 Clash 数据只有运行意图标记是否存在。它不读取或复制：

- 订阅链接、代理节点或认证信息；
- SharedPreferences、数据库和应用日志；
- 浏览记录、DNS 请求或网络流量内容。

模块没有 system overlay、网络下载逻辑或 SELinux 规则。它以 Root 运行是为了观察进程/服务状态和读取标记，因此只应安装来自本仓库源码构建并核对过摘要的 ZIP。

## 暂停、卸载与回滚

在 APatch 的模块页按 Action 可即时暂停；再次按下恢复。暂停不会停止已经运行的 Clash VPN。

彻底移除时，在 APatch 管理器中删除 `Clash Meta VPN Watchdog (ZTE W200DS)` 并重启。`uninstall.sh` 只结束自己的守护并清理 `/data/adb/clash_meta_watchdog`，不会停止 Clash、删除 Clash 数据、取消最近任务锁定、删除厂商窄白名单或更改 Android“始终开启 VPN”。

窄白名单必须按安装时记录的 `_id` 精确回滚，再重启刷新缓存；不要只按包名宽泛删除，也不要删除安装前就存在的行。下例的 `235` 只是本次真机记录，在其他设备上必须替换为实际记录值。删除前先按 `_id` 重新查询，手工核对包名、`type=1`、`used_module=6` 和 `compare_mode=1`：

```bash
CLASH_POLICY_ROW_ID=235
adb shell su 1000 -c "content query --uri content://com.zte.heartyservice.strategy.provider/special_class_list --where _id=${CLASH_POLICY_ROW_ID}"
# 只有上一行核对完全正确时，才执行下面的删除。
adb shell su 1000 -c "content delete --uri content://com.zte.heartyservice.strategy.provider/special_class_list --where _id=${CLASH_POLICY_ROW_ID}"
```

最近任务锁定和“始终开启 VPN”则分别在中兴最近任务菜单与系统 VPN 设置中关闭。
