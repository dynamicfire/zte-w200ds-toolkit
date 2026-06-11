# F9 自定义启动 — ZTE W200DS 云电脑平板

把键盘上的 F9 从"启动运营商云电脑"改成弹一个自定义应用选择器，同时保留 F9 触发的触摸→鼠标模式。设备需 root（Magisk）。不刷机、不改 system 分区、不用 LSPosed。

## 原理

F9 在这台机器上发的是 keycode 307（`LAUNCHER_SWITCH`，扫描码 250）。`PhoneWindowManager` 直接拦截它（日志 `direct switch default launcher`），不下发给应用层，所以用户态的按键映射 App 抓不到它。

拦截之后，框架会调 `InputManager-JNI: Setting cloud computer mode feature to enabled` 打开触摸→鼠标模式。这一步只认 F9 这个动作，跟启动哪个 App 无关，换目标 App 不影响鼠标模式。

接着系统应用 `com.zte.usmartlauncher`（uid 1000，平台签名，拿不到签名密钥所以改不了它的 APK）读 `Settings.System` 里的 `pc_switch_mode`，按值决定启动哪个云电脑：

| pc_switch_mode | 启动的包 |
|---|---|
| 0（兜底值）| `com.zte.mobile` / `com.zte.mspice.ui.WelcomeActivity`（空槽，未装）|
| 1 | cm.komect.aqb.android.cloudcomputerpad |
| 2 | com.ctg.itrdc.clouddesk（天翼，未装）|
| 4 | com.cmss.cloudcomputer.tablet（出厂默认）|
| 5 | com.aliyun.wuying.enterprise（无影，未装）|

模式 0 的 `com.zte.mobile` 是个空槽。做一个 applicationId 为 `com.zte.mobile`、带 `com.zte.mspice.ui.WelcomeActivity` 组件（用 activity-alias 指向真正的选择器）的普通 APK 占掉它，再把 `pc_switch_mode` 设成 0，F9 就会启动这个选择器，鼠标模式照常由框架开启。

## 安装

两样东西都要有，少一样都不行：

- APK `com.zte.mobile`：F9 启动的目标，也就是选择器。
- 系统设置 `pc_switch_mode=0`：告诉 usmartlauncher 去启动那个空槽。出厂值是 4（中移云电脑）；只装 APK 不改这个值，F9 还是进中移云电脑。

改 `pc_switch_mode` 需要 root。

```sh
SERIAL=<设备序列号>     # adb devices 查；只接一台可省略 -s

# 0) 编译 APK（仓库不带预编译产物；没有密钥时 build.sh 会生成一个 debug.keystore）
./build.sh

# 1) 装选择器
adb -s $SERIAL install -r PcSwitch.apk

# 2) 把 F9 指过来
adb -s $SERIAL shell su -c 'settings put system pc_switch_mode 0'
```

验证：

```sh
adb -s $SERIAL shell settings get system pc_switch_mode   # 0
adb -s $SERIAL shell pm path com.zte.mobile               # 有路径
```

不接电脑也行：APK 装好后，进系统自带的"云电脑选择"界面选"移动云电脑 / ZTE mspice"那一项，它就是把 `pc_switch_mode` 写成 0。

只在 ZTE W200DS 上验证过。换型号或固件，映射表、空槽包名、F9 的 keycode 都可能不一样，要重新排查。

## 使用

- 按 F9 弹出"进入 PC 模式 — 选择应用"列表，此时鼠标模式已开。
- 点一个 App 进去（默认列：移动云电脑、移动云电脑定制版、Moonlight）。
- 底部"⚙ 编辑列表…"打开全部已装应用的多选框，勾要显示的、保存。以后装了新 App（网易云游戏、UU 远控等）在这里加，不用重装也不用连电脑。选择存在 App 私有偏好里，重启保留。

## 竖屏 App 强制横屏

有些手机向的 App（网易云游戏等）把 Activity 锁成了竖屏，在横用的平板上会躺倒。打开显示的"忽略方向请求"，系统就不理会 App 的方向声明，按设备方向显示（DeX / Chromebook 那种大屏行为）：

```sh
adb -s $SERIAL shell su -c 'wm set-ignore-orientation-request true -d 0'
```

这个开关会写进 `/data/system/display_settings.xml`，重启保留。它是全局的，安卓桌面和所有 App 都受影响。

旋转行为另外设：

```sh
# 跟着体感转
adb -s $SERIAL shell settings put system accelerometer_rotation 1

# 或锁死横屏
adb -s $SERIAL shell settings put system accelerometer_rotation 0
adb -s $SERIAL shell settings put system user_rotation 1      # 1=90°，3=270°
```

想关掉这个特性：

```sh
adb -s $SERIAL shell su -c 'wm set-ignore-orientation-request false -d 0'
```

## 恢复出厂行为

F9 直接进中移云电脑：

```sh
adb -s $SERIAL shell su -c 'settings put system pc_switch_mode 4'
adb -s $SERIAL uninstall com.zte.mobile
```

## 重新编译

```sh
./build.sh install
```

仓库不带签名密钥，首次运行 `build.sh` 会生成 `debug.keystore`（口令 `android`）。原地更新已装的 App 要一直用同一个密钥；换了密钥签名对不上，得先卸载再装。

## pc_switch_mode 被改回时

目前没碰到。如果哪天被系统流程重置，可以在 Magisk 的 `service.sh` 里开机写回：

```sh
until [ "$(settings get system pc_switch_mode)" = "0" ]; do
  settings put system pc_switch_mode 0; sleep 2
done
```
