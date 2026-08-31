# W200DS 上运行 Ubuntu Server 26.04（QEMU/KVM）

本页记录 ZTE W200DS 在 Android 13、APatch Root、Termux 与 QEMU/KVM 下运行 ARM64 Ubuntu Server 的可重建方案。它是设备专用工具，不是通用 Android 虚拟机安装器。

## 已验证基线

以下组合已在一台 W200DS 上完成真实启动、SSH、正常关机和重启验证：

- Ubuntu Server 26.04 LTS ARM64 cloud image
- QEMU 11.0.3，`virt,accel=kvm`，guest 内 `systemd-detect-virt` 返回 `kvm`
- 2 vCPU，固定在 CPU 4–7 的同构 Cortex-A76 大核组（掩码 `f0`）
- 1536 MiB guest 内存
- 64 GiB 稀疏 qcow2 overlay
- QEMU user-mode NAT；SSH 只映射到 Android 回环地址 `127.0.0.1:2222`
- SELinux 保持 Enforcing；`/dev/kvm` 保持原厂 `system:system 0600`
- guest 禁止密码 SSH 和 root SSH，仅接受独立密钥

这只能证明上述设备和当前内核/固件组合。不同型号、OTA、CPU 拓扑、Root 实现或 QEMU 版本必须重新验收。

## 架构

```text
平板 Termux: u26 ──SSH──> 127.0.0.1:2222 ──> Ubuntu :22

Mac: u26 ──ADB USB 转发──> Android 127.0.0.1:2222 ──> Ubuntu :22
            │
            └──ADB shell + APatch su──> 启动/核验 Root QEMU
```

QEMU 以 Root 启动是为了使用原厂权限不变的 `/dev/kvm`。不要把 `/dev/kvm` 改成全局可读写，也不要把 SELinux 改为 Permissive。

## 目录结构

设备端脚本默认使用：

```text
/data/data/com.termux/files/home/
├── bin/
│   ├── connect-ubuntu26
│   ├── start-ubuntu26-root
│   ├── status-ubuntu26
│   ├── status-ubuntu26-root
│   └── stop-ubuntu26
├── .ssh/
│   └── w200ds_ubuntu26_ed25519       # 私钥，不入库
└── vm/ubuntu26/
    ├── base/                         # 只读 Canonical cloud image
    ├── disk/ubuntu26.qcow2           # 64 GiB 稀疏 overlay
    ├── firmware/edk2-code.fd         # 只读 AArch64 EDK2 code
    ├── firmware/edk2-vars.fd         # 本实例独立可写变量盘
    ├── seed/seed.iso                 # 含真实公钥，不入库
    ├── ssh/known_hosts               # 实例主机身份，不入库
    ├── log/
    └── run/
```

Mac 的长期私密状态默认放在：

```text
~/Library/Application Support/W200DS/ubuntu26/
├── config                            # 0600；含 ADB 序列号和私密路径
└── ssh/
    ├── id_ed25519                    # 0600；不入库
    └── known_hosts
```

Mac 命令入口安装为 `~/.local/bin/u26`。运行不依赖临时工作目录或 Git clone。

## 前提检查

在写入 VM 文件前先确认：

1. 型号和 CPU 架构符合预期。
2. APatch Root 正常，SELinux 仍为 Enforcing。
3. Root 可以打开 `/dev/kvm`；不要用 `chmod` 规避失败。
4. Termux 有足够空间，且 QEMU 是 AArch64 system emulator。
5. 已准备独立的 Mac 和 Termux SSH 密钥；不要复用或提交私钥。

已验证的 Termux 包名为：

```sh
pkg install coreutils openssh procps qemu-common \
  qemu-system-aarch64-headless qemu-utils util-linux iproute2
```

只读检查示例：

```sh
uname -m
qemu-system-aarch64 --version
su -c 'getenforce; ls -lZ /dev/kvm'
```

## Ubuntu 镜像与磁盘

本次验收固定使用 Canonical 正式发布目录中的 ARM64 cloud image：

```text
https://cloud-images.ubuntu.com/releases/resolute/release-20260823/ubuntu-26.04-server-cloudimg-arm64.img
SHA-256: 00e2d9f09373125eb9040952ae3b8d5553fe3df8f5004c08838473a1f61b74bf
```

下载后必须先与 Canonical 的 `SHA256SUMS` 和签名文件核对，再复制到 `base/`。`.img` 本身是 qcow2，不是 raw 镜像；创建 64 GiB overlay 时必须明确 backing format：

```sh
qemu-img create -f qcow2 \
  -F qcow2 \
  -b "$HOME/vm/ubuntu26/base/ubuntu-26.04-server-cloudimg-arm64.img" \
  "$HOME/vm/ubuntu26/disk/ubuntu26.qcow2" \
  64G
```

64 GiB 是虚拟上限，不会立刻占满 64 GiB；实际占用会随 guest 写入增长。

## EDK2 与 cloud-init

Termux QEMU 包提供：

```text
$PREFIX/share/qemu/edk2-aarch64-code.fd
$PREFIX/share/qemu/edk2-arm-vars.fd
```

为每台 VM 分别复制：

```sh
cp "$PREFIX/share/qemu/edk2-aarch64-code.fd" \
  "$HOME/vm/ubuntu26/firmware/edk2-code.fd"
cp "$PREFIX/share/qemu/edk2-arm-vars.fd" \
  "$HOME/vm/ubuntu26/firmware/edk2-vars.fd"
chmod 400 "$HOME/vm/ubuntu26/firmware/edk2-code.fd"
chmod 600 "$HOME/vm/ubuntu26/firmware/edk2-vars.fd"
```

`edk2-vars.fd` 会在运行中变化，不能多台 VM 共用，也不能只恢复 qcow2 而忽略同一恢复点的变量盘。

将 `tools/ubuntu-kvm/cloud-init/` 中的两个模板复制到私密工作目录：

1. 为 `instance-id` 生成唯一值。
2. 替换两把 SSH 公钥占位符。
3. 检查是否接受 `NOPASSWD:ALL` 风险。
4. 用 `cloud-localds` 或兼容的 NoCloud ISO 工具生成卷标为 `cidata` 的 `seed.iso`。

真实 `user-data`、公钥组合和生成后的 ISO 都不得提交。cloud-init 的 once-per-instance 行为由 `instance-id` 控制；复用已启动磁盘时不要指望相同 ID 重新执行初始化。

## 设备端脚本

公开源码位于 `tools/ubuntu-kvm/device/`。安装到设备时使用下列文件名：

| 仓库文件 | Termux 目标 |
|---|---|
| `u26.sh` | `$PREFIX/bin/u26` |
| `start-ubuntu26-root.sh` | `$HOME/bin/start-ubuntu26-root` |
| `status-ubuntu26-root.sh` | `$HOME/bin/status-ubuntu26-root` |
| `connect-ubuntu26.sh` | `$HOME/bin/connect-ubuntu26` |
| `status-ubuntu26.sh` | `$HOME/bin/status-ubuntu26` |
| `stop-ubuntu26.sh` | `$HOME/bin/stop-ubuntu26` |

部署前先备份已有脚本。设备脚本是 W200DS 专用的：固定 2 vCPU、1536 MiB、CPU 亲和性 `f0`、回环端口 2222，以及上面的目录结构。修改任何一项后都要重新运行状态和关机门禁测试。

把仓库复制或克隆到 Termux 后，在 Termux 普通用户下运行：

```sh
cd tools/ubuntu-kvm/device
./install.sh
```

安装器只写入上表中的六个命令，不触碰 VM、密钥或 cloud-init。若目标已经存在且内容不同，它会默认拒绝覆盖；核对差异后可用 `./install.sh --force`，旧文件会先保存为带时间戳的 `.before-*` 备份。安装后用 `ls -lZ "$PREFIX/bin/u26" "$HOME/bin/"*ubuntu26*` 检查 owner、执行位和 SELinux 标签。

在 APatch Manager 的“超级用户”页面只给 Termux 主应用授权，保持默认目标 UID 和 SELinux profile。不要手工编辑 `package_config`，因为 Manager 还需要同步内核 allowlist。

平板日常使用：

```sh
u26
```

或直接：

```sh
u26 go
u26 connect
u26 status
u26 stop
```

`u26 stop` 即使遇到 SSH 在关机时提前断开，也会继续核对 Root QEMU 状态；只有明确看到 `STATE=stopped` 才会报告成功。

## Mac 端安装与使用

从仓库安装公开命令和空配置模板：

```sh
tools/ubuntu-kvm/macos/install.sh
```

已有不同的 `~/.local/bin/u26` 时安装器会拒绝覆盖；确认它就是本工具的旧版后，运行 `tools/ubuntu-kvm/macos/install.sh --force`，旧文件会先备份。

然后：

1. 编辑 `~/Library/Application Support/W200DS/ubuntu26/config`，填写 `adb devices` 显示的实际序列号。
2. 把当前有效 Mac 私钥保存为 `ssh/id_ed25519`，权限设为 `0600`。
3. 把已核验的 guest 主机密钥记录保存为 `ssh/known_hosts`。
4. 确认 `~/.local/bin` 在 `PATH` 中。

之后 Mac 与平板使用同一记忆入口：

```sh
u26
```

也支持 `u26 go`、`u26 connect`、`u26 status` 和 `u26 stop`。

Mac 只通过 USB ADB 将本机端口转发到 Android 回环端口；脚本不会把 guest SSH 暴露到 Wi-Fi 或局域网。

## 验收

至少完成一次完整闭环：

1. `u26 start` 后确认 SSH 就绪。
2. `u26 status` 同时确认 QEMU 进程身份、全部线程亲和性、QMP `root:root 0600`、SSH 转发、guest `systemd`、`kvm` 和 64 GiB 虚拟盘。
3. 选择菜单“正常关机”，确认 `STATE=stopped`，QEMU 消失且 2222 不再监听。
4. 再次启动并连接，确认文件持久。
5. QEMU 完全关闭后运行 `qemu-img check`；运行中禁止触碰活动 qcow2。

不要把本地/静态检查写成通用真机结论。当前公开脚本的验收只覆盖上述 W200DS 基线。

## 恢复与安全边界

- 恢复点必须同时保存 qcow2 overlay 和该实例的 `edk2-vars.fd`。
- 恢复前必须持有与启动脚本相同的锁，并再次确认 `STATE=stopped`。
- 恢复会回退 guest 数据；不要把覆盖命令放在日常 README 中直接复制执行。
- QMP socket 保持 `root:root 0600`，不要暴露给普通应用或网络。
- 不设置 Android 开机自启、后台保活、电池白名单或网络桥接，除非另行设计和验收。
- 当前没有 GPU 加速；完整 Ubuntu Desktop 对 1536 MiB guest 偏重。若以后需要 GUI，优先在同一 Server 安装轻量桌面与 xrdp/VNC，并重新观察 Android 内存压力。

## 撤销与保留数据

先运行 `u26 stop` 并确认 `STATE=stopped`。如果 Mac 端仍有转发，可运行 `adb forward --remove tcp:2222`；这只移除当前 ADB 映射，不影响 guest 文件。

- 移除 Mac 的 `~/.local/bin/u26` 只会移除命令入口，`~/Library/Application Support/W200DS/ubuntu26/` 中的配置和 SSH 身份仍会保留。
- 移除 Termux 中上表列出的六个脚本只会移除管理入口，不会删除 VM。
- `vm/ubuntu26/` 才包含 guest 本体。删除它会失去 Ubuntu 系统和 guest 数据；必须在 QEMU 完全退出后，先备份 qcow2 overlay 与同一恢复点的 `edk2-vars.fd`。
- 不再运行 VM 时，可以最后在 APatch Manager 撤销 Termux 的超级用户授权。撤权不会自动删除 VM，也不会恢复已删除的数据。
- 私钥与 known_hosts 可以作为迁移材料保留；若决定销毁，先确认没有其他备份或自动化仍依赖它们。本仓库不提供一键删除命令。

## 上游资料

- [Canonical 固定镜像目录](https://cloud-images.ubuntu.com/releases/resolute/release-20260823/)
- [Canonical SHA256SUMS](https://cloud-images.ubuntu.com/releases/resolute/release-20260823/SHA256SUMS)
- [Ubuntu QEMU cloud image 指南](https://ubuntu.com/docs/public-images/public-images-how-to/launch-qcow-with-qemu/)
- [Ubuntu 本地 cloud-init datasource](https://ubuntu.com/docs/public-images/public-images-how-to/use-local-cloud-init-ds/)
- [QEMU Arm virt 平台](https://www.qemu.org/docs/master/system/arm/virt.html)
