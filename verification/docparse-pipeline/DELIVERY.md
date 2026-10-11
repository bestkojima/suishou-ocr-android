# docprase 页面流水线接入与 0.7.8 交付

用户授权删除 core 转发层、接入 App、构建两版 APK、提交上传代码并发布。版本为 `0.7.8-ocr`／`versionCode=15` 预发布。

## 最终结构

- 删除 `core.cpp`；公共 `run_page` 直接在 `page_pipeline.cpp` 实现，删除重复的 `run_page_pipeline` 声明。
- 页面身份、版面推理与解码、候选筛选/复核、页面计划、区域执行、识别结果处理、图像资源及文档导出分别由模块承担，pipeline 组合阶段并负责预算、取消和最终提交。
- 生产路径仍为 PP-DocLayoutV3 + 区域 VLM，保留 Android 模型目录配置及 Ovis/GLM 适配。旧通用接口是兼容测试路径；没有实现 PP-Structure。
- App 的 `native/docprase` 与源提交 `c23b4f033b46ce022c23b9f129fd81b8b100c140` 的 104 份构建文件逐字节相同；依赖清单重建了 SHA-256，编译记录使用快照的 pipeline，不包含 docprase core.cpp。

## 本地验证

- 删除转发层后重新构建，CTest 41/41 一次通过，92.52 秒，见 [日志](ctest.log)。
- App 普通版和测试版各 74 项 JVM 测试，无失败/错误/跳过；两版 lint 均 0 错误、11 项既有警告；两版 APK 构建成功，见 [构建日志](app-build.log)。
- 已对接入后的两版 APK 检查 arm64、C ABI/JNI/LLM 符号、动态依赖闭合、实际编译 Release/-O3/NDEBUG、无 MNN 调试宏、裁剪功能/OpenCV/许可及权重排除。见 [接入静态检查](apk-checks-integration.json) 和 [源文件绑定](source-integration.json)。
- 新版本号的最终构建、签名/应用标识/版本检查及上传前 SHA 由现有 `tools/release.py` 执行，完整报告保存在 `artifacts/releases/v0.7.8/`。

此前第 8 轮已完成 118 组前后行为对照和 ASan/UBSan 验证，本轮入口删除的回归结果为上述 41 项。

## 发布与限制

源仓库已提交并推送此版本。Android 发布脚本提交项目全部未忽略改动，包括已有模型计时与异常诊断记录；这些记录不代表本轮修改识别策略或修复 BUG-002。脚本普通推送代码和标签，上传普通版、测试版与 SHA256SUMS.txt；上传成功后即结束，不查询远端资产或下载复核。

发布说明：[v0.7.8](../../docs/releases/v0.7.8.md)。发布状态、Android 来源提交及最终 URL 以本地 `artifacts/releases/v0.7.8/state.json` 为准。

此轮未进行 Android 真机 OCR 或 Titan_1 性能/内存验收；不宣称准确率提升。保留原签名、应用标识、固定 MNN 与 `use_mmap=false`／`kvcache_mmap=false`。
