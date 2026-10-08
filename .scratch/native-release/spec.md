# 0.7.5 原生 Release 优化与关闭 mmap

Status: ready-for-agent

保留 userDebug/labDebug APK、应用标识及现有开发签名。全部生产 C/C++ 使用 Release/-O3/NDEBUG，MNN 不含 DEBUG/MNN_DEBUG。Ovis 语言与视觉运行配置固定 use_mmap=false、kvcache_mmap=false；使用现有私有临时目录，不创建／复用 ovis-mmap-*，不删除用户旧缓存。

版本为 0.7.5-ocr / versionCode 12。构建、两版 JVM/lint、浏览器和 Linux 同源真实模型 A/B 通过后发布 GitHub v0.7.5 预发布及两版 APK／SHA256SUMS。真机未连接不阻止预发布，但需明确标注性能／内存待验收。正式版仅在用户明确宣布后命名为 1.0.0。

## 验收

APK ELF build ID 唯一关联当前 AGP 原生构建；检查实际编译命令最终 -O3、NDEBUG 及 MNN 调试宏关闭。有效运行配置、manifest 与日志均为 mmap=false。用同模型／线程／输入比较 Debug+mmap=true、Release+mmap=true、Release+mmap=false，并分别记录加载／首内容／vision／prefill／decode／总耗时／内存。正文／公式／表格差异须解释。保留历史原始证据，桌面指标不作为真机指标。

## Comments

2026-10-08：用户明确要求实施并先发预发布；本工作区为 /home/dr/project/android_ocr，ADB 当前为空。交接单中的 r0014 与 Titan_1 日志不在本工作区，以仓库真实样图完成同源回归，设备对照留待后续。
