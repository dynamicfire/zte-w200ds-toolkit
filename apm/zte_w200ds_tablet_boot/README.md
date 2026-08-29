# ZTE W200DS Default Tablet Boot

这是一个最小化的 APatch APM 模块。它只在启动时覆盖下面这项属性：

```properties
ro.vendor.feature.zte_feature_show_smart_launcher_when_boot=false
```

作用是阻止中兴固件开机时强制显示“平板模式 / 云电脑模式”选择页。模块不会停用 USmart Launcher，也不会替换原厂模式切换 Provider，因此 F9 链路仍然存在。

## 模块内容

- `module.prop`：模块元数据。
- `system.prop`：由 APatch 在启动时覆盖属性。
- `skip_mount`：声明本模块不挂载或替换系统文件。
- 没有 `service.sh`、`boot-completed.sh`、常驻进程、网络访问、SELinux 规则或系统 APK。

从云模式关机后自动回到平板桌面的逻辑位于配套 F9 选择器 v1.8 或更高版本。选择器只在持久化 HOME 明确为 USmart 时调用至多一次原厂 `switch_mode` 拉起 MiFavor；随后无论初始状态是 USmart 还是 MiFavor，都安排一次 guarded finalizer。它只有在本次开机会话仍有效、未被 F9 取消，且连续两次确认 `secure` 已是 MiFavor、实际 HOME resolver 仍是 USmart 的半状态时，才用单向 `switch_pad` 固化 HOME。免权限的 inexact alarm 最早约 10 秒触发，也可能被系统延后。

## 构建与安装

在仓库根目录运行：

```bash
./scripts/build-apm.sh
```

生成的 ZIP 位于 `build/outputs/`。请在 APatch 管理器中安装并重启；它不是 Recovery 刷机包。

安装前确保原厂组件保持启用：

```bash
adb shell su -c 'pm enable --user 0 com.zte.usmartlauncher/.activity.UsmartMainActivity'
```

## 回滚

在 APatch 管理器中停用或卸载本模块，然后重启。也可以在仍有 ADB Root 时执行：

```bash
adb shell su -c 'touch /data/adb/modules/zte_w200ds_tablet_boot/disable'
adb reboot
```

重启后该属性恢复固件原值，原厂开机模式选择逻辑随之恢复。USmart Launcher 的主 Activity 必须保持启用。

## 兼容性

只在中兴 W200DS 的当前 Android 13 固件上完成真机验证。其他型号、固件或 OTA 后必须重新确认属性名和厂商组件行为。
