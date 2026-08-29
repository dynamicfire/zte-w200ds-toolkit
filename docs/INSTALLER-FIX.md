# W200DS Installer Fix：调查、实现与真机验证

本文记录 Installer Fix 1.4 的固件依据、修改边界和回归结果。它不是通用 Android
安装器模块；所有私有类名和资源名都绑定到已验证固件。

## 已验证环境

```text
设备：ZTE W200DS
Android：13
系统版本：MyOS13.0.29_W200DS_CMGEN
系统安装器：13.0.000.000.2409021359 / versionCode 130015
模块：io.github.dynamicfire.zte.installerfix 1.4 / versionCode 5
框架：Vector
作用域：com.android.packageinstaller/0
验证日期：2026-08-30
连接：有线 USB ADB
```

## 原始问题

当前固件接管了 AOSP 安装器入口，表现为：

- 点 APK 后先全屏显示“安全检测中”；
- 随后的确认页虽然能切回接近原生的样式，但 Manifest 仍把 Activity 当全屏页面，
  会出现强制全屏、黑底或主题颜色错误；
- 安装成功页也是全屏，并显示“已删除安装包和残留”；
- 隐藏的删除开关会让安装器尝试删除用户选择的源 APK。

这些行为与 APK 签名校验、未知来源授权和 PackageManager 安装校验不是同一层。

## “安全检测中”的实际链路

全屏页面来自厂商 `com.android.packageinstaller.InstallScanning`，不是 Android 安装
所必需的 AOSP 确认页。现场日志显示它会反复尝试访问当前系统不存在的：

```text
com.zte.anti_virus.intent.action.startService.VIRUS_SCAN
package: com.zte.heartyservice
content://com.zte.heartyservice.antifraud.provider
```

同时观察到安装器向努比亚/中兴应用商店接口发起 HTTPS 请求：

```text
https://api-appstore-outside.nubia.cn/ZteSoft/ZTE/GetSoftByPackagename
https://api-appstore-outside.nubia.cn/ZteSoft/ZTE/GetWhiteList
https://api-appstore-outside.nubia.cn/ZteSoft/ZTE/CheckSoftLimitStatus
```

请求参数包含待安装包名、`AppId=10004`、时间戳和请求签名。静态分析与现场日志中
没有发现这个安装器把完整 APK 上传到这些接口的证据。固件的本地反诈 Provider
接口还设计为接收包名、签名 MD5/SHA-1/SHA-256，部分路径会传 APK MD5；但对应
Provider 在本机缺失，因此无法完成。

安装器里虽然存在 `setting_sp/virus_scan` 与 `cloudVirusScanEnabled()`，当前构建的
实际扫描路径没有调用这个 getter。单纯修改隐藏开关既不能阻止页面闪现，也不能
消除缺失服务造成的失败。

## “已删除安装包和残留”的实际含义

当前固件的 `InstallInstalling.delSourcePkgA()` 只会：

1. 读取 `setting_sp/key_del_pkg`；
2. 读取 `del_apk_path`；
3. 确认字符串以 `.apk` 结尾；
4. 对这一个路径执行 `File.delete()`。

它不会递归扫描“残留”，也没有检查 `File.delete()` 的返回值。成功页中的大小来自
`del_apk_size`，默认值为 0，并通过 `#.00` 格式化；因此“已删除 0.00MB……”不能
证明任何文件真的被删除。

必须区分两类文件：

- **用户源 APK**：文件管理器/下载目录里用户选择的原文件，应保留；
- **安装器暂存副本**：`content://` 安装时复制到安装器私有 `no_backup` 目录的临时
  文件，安装结束后应继续清理。

Installer Fix 只阻止第一类删除，不 Hook `File.delete()`，也不修改
`DeleteStagedFileOnResult`。

## 实现

| 位置 | 修改 | 保留的行为 |
|---|---|---|
| `InstallStart.getCallingPackageNameForUid()` | 触发固件已经存在的 CTS 路由 | 原 Intent、数据、flags 与结果契约 |
| `Activity.startActivity(Intent)` | 仅把目标为 `InstallScanning` 的内部跳转改道 | direct 与 staged 两条原有分支 |
| `CtsPackageInstallerActivity.onCreate()` | 提前应用厂商 Dialog 主题，随后补背景、透明任务记录与 `0.30` dim | 原 AlertActivity 内容和按钮 |
| `PackageUtil` 的 `key_del_pkg` getter/setter | 只对这个键强制 `false` | 其他安装器设置 |
| `InstallInstalling.delSourcePkgA()` | 跳过单一源 APK 删除函数 | 暂存副本清理 |
| `InstallSuccess.onCreate()` | 应用悬浮主题、隐藏误导文案、限制卡片宽度 | 原“完成/打开”按钮和监听器 |

对 `content://` 安装，模块仍启动固件的 `DeleteStagedFileOnResult`，并设置原固件
已经支持的 `isCtsInstall=true`。这个包装 Activity 负责删除私有暂存副本和向来源
应用回传最终结果。

所有 Hook 都限定在 `com.android.packageinstaller` 进程，并以 fail-open 方式捕获
异常：若未来固件不兼容，尽量保留原厂目标和窗口状态，而不是扩大到系统框架或
通用文件 API。

## 安全与隐私取舍

启用模块后仍然存在：

- APK 结构解析和签名验证；
- 已安装应用更新时的签名匹配；
- 未知来源授权；
- 明确的用户确认；
- PackageManager 的安装期校验。

被移除的是中兴附加的云信誉、反诈和商店限制信号。它在本固件上依赖组件缺失，
但在其他固件上可能正常工作并拦截风险应用。启用模块后应自行核验 APK 来源、签名
和哈希；不要把“恢复原生安装器”理解成获得了等价的恶意软件检测能力。

模块 APK 自身不声明网络、存储、Root 等 Android 权限。Xposed 模块代码仍会在
系统安装器进程中执行，所以 Vector/LSPosed 作用域只能勾选：

```text
com.android.packageinstaller/0
```

不要勾选 Android 系统框架或其他应用。

v1.4 还会把安装器私有偏好 `setting_sp/key_del_pkg` 持久化为 `false`，避免模块
加载时序变化重新开启源 APK 删除。这个单一偏好变化会在停用模块后保留；它不是
system 分区修改，但必须计入回滚边界。

## 真机回归结果

Installer Fix 1.4 已完成以下可见与日志回归：

- 普通文件和 `content://` 两类安装都进入悬浮确认框；
- 确认框外仍能看见来源应用，背景遮罩约为 `0.30`，不再纯黑；
- 白色卡片与文字颜色正常，日间主题没有黑字黑底；
- 不再创建全屏 `InstallScanning` 页面；
- 成功页为居中悬浮卡片，“完成”和“打开”均保留；
- 不再显示“已删除安装包和残留”；
- 用户源 APK 保留；
- staged 私有副本在按“完成”退出后消失，结果转发链没有被破坏。

最终流程日志包含：

```text
Routed installer confirmation through CTS UI
Skipped vendor scan while preserving staged cleanup
Applied confirmation dialog theme/background/translucency/dim
Blocked vendor source APK deletion
Applied success dialog theme/background/translucency/dim
Hidden misleading source-deletion result
```

测试结束后没有为了“清理干净”而批量删除安装器 `no_backup` 目录的历史文件：本机
仍有旧固件流程留下的 `package*.apk` 和未提交 Session，部分可能仍被 PackageInstaller
会话引用。模块只保证新测试链路的 staged 文件按原包装层回收。

## 安装后检查

使用可信的小型 APK，分别从文件管理器和能提供 `content://` URI 的应用发起安装：

1. 确认不再闪现全屏“安全检测中”；
2. 确认安装确认页与成功页都是悬浮卡片；
3. 确认“完成”和“打开”可用；
4. 返回来源目录，确认源 APK 仍存在；
5. 用有线 ADB 检查模块日志和安装器私有 staged 文件是否在流程结束后删除。

每次系统 OTA 后至少重复上述测试。若确认页无法打开、窗口异常或安装结果不能返回，
立即停用模块，不要通过扩大作用域补救。

## 停用与回滚

1. 在 Vector/LSPosed 中停用 Installer Fix；
2. 强行停止“软件包安装程序”，或重启设备；
3. 再发起一次测试安装，确认路由、云检测页和窗口恢复固件原行为；
4. 不再需要时可卸载 `io.github.dynamicfire.zte.installerfix`。

`key_del_pkg=false` 会继续保留，因此源 APK 自动删除可能仍为关闭。若确实要恢复
这个行为，只能恢复安装前备份的该键原值；没有备份时不要猜测，也不要为了一个
偏好清空整个系统安装器数据。

模块不改 system 分区、厂商 APK 或全局网络配置，回滚 UI/路由不需要刷机。

## 仓库卫生

仓库只保留模块源码、最小 compile-only Xposed stub 和可移植构建脚本。以下调查
材料明确不入库：系统 `framework.jar`/DEX、厂商 PackageInstaller APK 或反编译树、
Vector 数据库备份、签名密钥、测试探针、截图、构建产物和 APK。
