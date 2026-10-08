# 原生 Release 配置及 APK 编译守卫

Status: ready-for-agent

- [x] 保留 0.7.4 基线并验证守卫先失败
- [x] 两版原生 Release/-O3/NDEBUG；版本 0.7.5/12
- [x] 检查 APK 对应编译记录、ABI、动态依赖及签名

## Comments

2026-10-08：开始实施，证据保存于 verification/native-release。

2026-10-08：本地验证已完成；原生编译、mmap 关闭与真实内容差异复核通过，设备验收仍待连接。
