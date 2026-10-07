# 03：识别各阶段耗时

Status: ready-for-agent

- [x] 记录每个生成尝试的 firstContentMs、visionMs、prefillMs、decodeMs、outputTokens、elapsedMs。
- [x] 验证当前实际配置为 CPU 单线程，区域串行，Android 构建没有启用 GPU 后端。
- [x] 用同源实际模型定位样图耗时主要在 prefill，区分展示延迟与计算时间。
- [ ] 设备连接后记录平板的实际阶段耗时、内存、温度和 CPU 行为，再决定线程／后端优化。

## Comments

2026-10-07：桌面样图首正文约 4.55s、整页约 23.28s；视觉 4.05s、prefill 14.71s、decode 2.99s。此轮与 APK 构建并行，是诊断记录而非独占资源性能基准；不能将这些秒数解释为平板速度。
