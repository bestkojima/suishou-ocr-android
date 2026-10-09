# 随手识别 0.7.6：拍照后调整文档边界，再确认识别

本次实现 BUG-005。此前拍照后直接启动 OCR；现在先自动检测文档边界，用户调整、预览并确认后才识别，减少文档外的背景输入。

## 修改

- 拍照新增裁剪阶段：自动边界检测、拖动四角、移动整个裁剪框、透视校正预览，以及“确认并识别”。检测失败时回退整张照片，可手动调整或使用全图。
- 识别直接使用确认预览的同一份 PNG。取消不启动 OCR，可重新拍照，未确认照片可从历史恢复。导入图片沿用现有流程。
- 保留原始照片和未裁剪底图。调整识别分辨率或重新识别继续使用已裁剪输入；重新裁剪已有结果时另存记录，保留旧结果和校对。ZIP 导出保留相关原图及输入。
- 固定引入 OpenCV 4.12.0 进行边界检测和透视裁剪，补充许可与 APK 依赖检查。两版发布安装包约 50.8 MB，模型仍需在应用内另行下载。
- 版本为 `0.7.6-ocr`，`versionCode=13`。保留现有应用标识、Debug 变体和签名；MNN／docprase／JNI 继续使用 Release／`-O3`／`NDEBUG`，`use_mmap=false`、`kvcache_mmap=false`。

## 安装

- `suishou-ocr-0.7.6-arm64.apk`：普通版，应用 ID `cn.local.ocr`。
- `suishou-ocr-0.7.6-lab-arm64.apk`：测试版，应用 ID `cn.local.ocr.test`，可与普通版共存。
- `SHA256SUMS.txt`：两个 APK 的 SHA-256 校验值。

需要 Android 8.0／API 26 以上、arm64-v8a，以及现代 Android System WebView（Chromium 100+）。同类型 APK 可覆盖安装并保留模型与记录。

## 验证及待验收

两版 JVM 测试各 68 项通过，lint 各 0 错误、11 项既有警告；裁剪界面 4 组及既有界面、识别、导航、流式回归通过。APK 通过 arm64、ABI／JNI、原生 Release、动态库依赖、模型权重排除、OpenCV 许可和原签名兼容检查。

Linux 使用同一生产 Java 图像算法验证倾斜文档、背景杂物、低对比度、无边界回退和全图像素保持；浏览器通过模拟 Android 桥验证交互流程。Linux 验证库为 OpenCV 4.9.0，APK 为 4.12.0，这些验证不能代替 Android 真机验收。

这是预发布。Titan_1 的拍摄方向、边界检测效果、触摸体验、裁剪后的真实 OCR、连续识别、性能及内存仍待验收；尚未宣称检测率或 OCR 准确率已达标。本次未修复 BUG-001 插图流式显示、BUG-002 重复生成、BUG-003 相机预览范围和 BUG-004 错误 LaTeX 校对问题，继续保留在 `bugdoc.md`。v0.7.5 与 v0.7.4 保留。

完整实现与证据见仓库 `verification/document-crop/DELIVERY.md`。
