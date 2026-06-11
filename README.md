# F9 自定义启动 — ZTE W200DS 云电脑平板

把平板键盘上的 **F9** 从"启动运营商云电脑"改成"弹出自定义应用选择器"，
并且**保留 F9 自带的触摸→鼠标模式**。已 root（Magisk），但本方案**不改系统、不用 LSPosed**，随时可逆。

## 如何安装

> 本方案 = **一个 APK** + **一个系统设置**，缺一不可，且都需要 root：
> - **APK**（`com.zte.mobile`）= "被 F9 启动的目标"（我们的选择器）。
> - **系统设置 `pc_switch_mode`** = "告诉 F9 去启动哪个槽位的指针"，存在 Android 系统设置数据库里
>   （和屏幕亮度同一个库，不是 App 内的设置）。
>
> **只装 APK 不够**：出厂 `pc_switch_mode=4`，F9 仍会启动中移云电脑、完全不理我们的 APK。
> 必须把它改成 0，F9 才会转去启动我们的选择器。

在电脑上用 adb（设备需已开 USB 调试、已 root）：

```sh
SERIAL=<你的设备序列号>     # 用 `adb devices` 查；只接一台时可省略 -s

# 0) 编译出 APK（仓库不含预编译产物；缺签名密钥时 build.sh 会自动生成 debug.keystore）
./build.sh

# 1) 安装选择器 APK
adb -s $SERIAL install -r PcSwitch.apk

# 2) 让 F9 指向它（需要 root；mode 0 = 空槽 com.zte.mobile）
adb -s $SERIAL shell su -c 'settings put system pc_switch_mode 0'
```

验证：

```sh
adb -s $SERIAL shell settings get system pc_switch_mode   # 应为 0
adb -s $SERIAL shell pm path com.zte.mobile               # 应有安装路径
```

> 不连电脑也行：APK 装好后，进系统自带的"云电脑选择"界面、选"移动云电脑 / ZTE mspice"那一项，
> 也会把 `pc_switch_mode` 设成 0。但 adb + root 一条命令最稳、最确定。

> ⚠️ 本方案是针对 **ZTE W200DS** 这台的固件逆向出来的：同型号、同系统可直接照搬上面两步；
> 换别的型号/品牌，映射表、那个恰好为空的 `com.zte.mobile` 槽位、F9 的 keycode 都可能不同，需重新排查。

## 如何使用

1. 按键盘 **F9** → 弹出"进入 PC 模式 — 选择应用"列表（此时触摸→鼠标模式已由系统开启）。
2. 点列表里要进入的 App（默认：移动云电脑 / 移动云电脑定制版 / Moonlight）。
3. **改列表**：点底部 **"⚙ 编辑列表…"** → 在全部已装应用里勾选/取消 → 保存。
   以后装了新 App（网易云游戏、UU 远控等），在这里勾上即可，**无需重装、无需连电脑**。
   选择保存在 App 私有偏好里，**重启不丢**。

## 工作原理（逆向结论）

1. F9 在本机发的是 **keycode 307 = `LAUNCHER_SWITCH`**（扫描码 250），由框架 `PhoneWindowManager`
   拦截（日志 `direct switch default launcher`），**不会下发给任何 App**。
2. 框架在这条"切换"流程里调用 `InputManager-JNI: Setting cloud computer mode feature to enabled`
   —— 这就是**触摸→鼠标的总开关**，只认 F9 这个动作，和启动哪个 App 无关。
3. 系统应用 `com.zte.usmartlauncher`（uid 1000，平台签名，**不可改 APK 重签**）接手，
   读 `Settings.System / pc_switch_mode` 决定启动哪个"桌面"：

   | pc_switch_mode | 启动的包 |
   |---|---|
   | 0（默认/兜底） | `com.zte.mobile` / `com.zte.mspice.ui.WelcomeActivity` ← 空槽 |
   | 1 | cm.komect.aqb.android.cloudcomputerpad |
   | 2 | com.ctg.itrdc.clouddesk（天翼，未装）|
   | 4 | com.cmss.cloudcomputer.tablet（原默认）|
   | 5 | com.aliyun.wuying.enterprise（无影，未装）|

4. **方案：占用模式 0 的空槽 `com.zte.mobile`。** 做一个 applicationId=`com.zte.mobile`、
   且带 `com.zte.mspice.ui.WelcomeActivity` 这个组件（用 activity-alias）的普通 APK；
   再 `settings put system pc_switch_mode 0`。于是 F9 → usmartlauncher 启动我们的选择器，
   鼠标模式照常由框架开启。

## 当前部署状态

- 设备已安装 `com.zte.mobile`（本工程产出的 `PcSwitch.apk`）。
- `Settings.System pc_switch_mode = 0`。
- 选择器默认列出：移动云电脑(cmss) / 移动云电脑定制版(komect) / Moonlight，以及"⚙ 编辑列表…"。

## 一键恢复成出厂行为（F9 直接进中移云电脑）

    adb -s <你的序列号> shell su -c 'settings put system pc_switch_mode 4'
    adb -s <你的序列号> uninstall com.zte.mobile

## 重新编译 / 更新 App

    ./build.sh install

> 仓库未包含签名密钥；首次运行 `build.sh` 会自动生成 `debug.keystore`（口令 `android`）。
> 注意：要**原地更新**已装的 App，必须一直用同一个 keystore；换了密钥会因签名不一致装不上，需先 `uninstall` 再装。

## 可选加固

若发现 `pc_switch_mode` 被系统某些流程改回（目前未观察到），可加一个 Magisk
`service.sh` 开机脚本强制写回 0：

    until [ "$(settings get system pc_switch_mode)" = "0" ]; do
      settings put system pc_switch_mode 0; sleep 2
    done
