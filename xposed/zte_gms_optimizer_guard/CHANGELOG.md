# Changelog

## 0.1.0

- 针对 P720P01 Android 13 精确固件的 legacy Vector/Xposed system_server 模块。
- VPN 活跃和 30 秒断开宽限期间阻止 `zte_fw_gms` UID DROP。
- 取消残留 Google 检查消息，并拦截在途旧任务的 `optimize(0)`。
- VPN 不使用时重新进入原厂省电策略。
- PowerManager 路径只读缓存；OEM 清理操作统一到专用 Handler。
- 加入固件门、部分 hook 回滚、VPN callback 有限重试和 fail-open。
