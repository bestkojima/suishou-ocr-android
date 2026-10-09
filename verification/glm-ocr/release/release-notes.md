# 随手识别 0.7.7：GLM-OCR 模型适配与切换

本次把 GLM-OCR 接入 Android App。此前应用识别使用固定 Ovis 工件、提示词和图像 token；现在可在“设置 → 识别模型”选择 OvisOCR2 或 GLM-OCR，并由模型目录配置加载配套工件和运行参数。Ovis 仍为默认选择。

## 改进

- 新增 GLM-OCR 模型选择、ModelScope 配套文件下载和加载入口，支持独立 embedding、tokenizer.txt 及语言/视觉模型。切换模型先卸载空闲引擎，识别或下载期间禁止切换。
- 配置驱动适配：模型目录的 config 字段提供文件引用、采样与正文/公式/表格提示词；llm_config.json 提供聊天模板、图像 token 与视觉参数。原始下载配置保留校验契约，同目录生成可编辑的 ocr_runtime.json，原生后端不依据目录名字决定模型。
- 保留完整 PP-DocLayoutV3 链路：图片先进行 800×800 版面检测，检测框映射回原图，再裁剪/缩放各区域交给 GLM-OCR，正文、公式、表格采用各自提示词。正文在真实模型生成过程中实时显示。
- 连续区域复用同一个模型并重置会话；加载后核对实际配置，日志/manifest 记录实际模型、图像 token、采样和线程参数。文档流程说明改为通用识别名称，避免 GLM 结果被标成 Ovis。
- 保持原生 MNN／docprase／JNI 的 Release／-O3／NDEBUG，use_mmap=false、kvcache_mmap=false。完整 SHA 仅在下载完成时校验，加载不重复扫描大权重；模型不随 APK 打包。
- 版本为 0.7.7-ocr，versionCode=14。沿用原应用标识、Debug 变体和签名；0.7.6 的拍照后文档裁剪继续保留。

## 实测结果与限制

在 Linux 虚拟机、CPU 4 线程、greedy 的真实对照中，用户 6800×9067 扫描页的 GLM-OCR 整页用时 113.17 秒，17 个区域正常停止；历史 Ovis 对照为 175.56 秒并有循环/重试。本样本耗时减少约 35.5%，其中包含避免异常生成的收益，不能作为通用加速比例。

三个此前出现日期循环、递增空编号和句点循环的实际区域，在 GLM 下各 3/3 正常停止。正常首区域的 Ovis／GLM 首内容分别约 2.93／4.98 秒：GLM 的 prefill/decode 更快，视觉处理更慢，不能宣称所有区域更快。

20×8 的低像素区域虽然不循环，仍将“商品统计”错识为“商品设计”；大字“识”的实际区域三次正确结束。BUG-002 保持未关闭，未将惩罚或字符串截断实验变成默认修复，也未宣称根因或总体准确率已验收。中文/公式/表格混合样本正常输出，真实 trace 确认按任务使用 GLM 提示词。

## 安装和模型准备

- suishou-ocr-0.7.7-arm64.apk：普通版，应用 ID cn.local.ocr。
- suishou-ocr-0.7.7-lab-arm64.apk：测试版，应用 ID cn.local.ocr.test，可与普通版共存。
- SHA256SUMS.txt：两版 APK 的 SHA-256 校验值。

需要 Android 8.0／API 26 以上、arm64-v8a 和现代 Android System WebView（Chromium 100+）。同类型覆盖安装保留已下载模型和文档记录。

安装后打开“设置 → 识别模型”，选择 GLM-OCR，浏览 MNN/GLM-OCR-MNN 仓库并下载配套配置、词表、embedding 和权重，然后加载。已有校验通过的 PP-DocLayoutV3 模型可以复用。要继续用 Ovis，选择 OvisOCR2 即可。

修改 ocr_runtime.json 后，需要重启应用或切换到另一模型再切回来并加载；已加载引擎不在区域之间热更新配置。

## 验证及待验收

两版 JVM 测试各 74 项通过，lint 各 0 错误、11 项既有警告；原生配置测试、浏览器模型选择、真实流、导航和裁剪回归通过。两版通过 arm64／ABI／JNI、原生 Release、动态库依赖、模型权重排除和原签名兼容检查。

这是预发布。Titan_1 未连接，设备端 GLM 的实际加载、识别、流式 UI、连续识别、速度和内存仍待验收；Linux 与浏览器模拟不代替真机验收。GLM 整页 Linux 进程峰值 RSS 约 2.90 GiB，包含输入解码和诊断采集，不能当作设备独立模型内存指标。

BUG-001 插图流式显示、BUG-002 重复生成、BUG-003 相机预览范围、BUG-004 错误 LaTeX 校对仍保留。v0.7.6、v0.7.5 和 v0.7.4 保留。

配置说明见仓库 docs/MODEL_CONFIG.md；真实对照与发布证据见 verification/glm-ocr/DELIVERY.md。
