# W200DS Ubuntu/KVM 工具

这里保存 W200DS 在 Termux + APatch Root + QEMU/KVM 下运行 ARM64 Ubuntu Server 的可重建脚本。

- `device/`：安装到 Termux 的设备端脚本和安全安装器；日常入口为 `u26`。
- `macos/`：Mac 端统一入口，也安装为 `u26`；真实序列号和 SSH 路径只放在本机私有配置中。
- `cloud-init/`：不含任何真实密钥的 NoCloud 模板。

完整前提、安装、验证与安全边界见 [../../docs/UBUNTU-KVM.md](../../docs/UBUNTU-KVM.md)。

本目录绝不保存 SSH 私钥、known_hosts、真实 cloud-init seed、qcow2、基础镜像、UEFI 变量盘、快照或运行日志。
