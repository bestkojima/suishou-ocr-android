# BUG-005：拍照后的文档裁剪交付

日期：2026-10-09（Asia/Shanghai）。版本 `0.7.6-ocr`／`13` 已按用户授权发布为 GitHub v0.7.6 预发布，附两版 APK、校验文件与中文修改说明。下载复核通过；保留既有 v0.7.5 和 v0.7.4。

## 当前行为

拍照后：自动检测边界 → 拖动四角或移动整个框 → 预览透视校正后的文档 → 确认并识别。确认前不会自动识别，直接调用识别入口也会被拒绝。边界检测失败回退整张照片，允许手动调整。取消保留输入，重新拍照返回相机；未确认记录可从历史恢复。

识别使用预览确认的同一 PNG，避免确认时再次裁剪改变内容。分辨率策略变化以已裁剪输入为源，不恢复文档外背景。原始 `photo.jpg` 和规范化的未裁剪底图保留；已有 OCR 结果重新裁剪会另存新记录。ZIP 导出包含相机原照片、底图及裁剪输入，校对/排序和重识别仍兼容。

## 实现与依赖

- `DocumentBoundaryDetector`：小图模糊/Canny/闭运算及 Otsu 两路轮廓，选择符合面积和凸四边形约束的最大文档候选；检测不是可靠性保证，用户必须核对预览。
- `CropGeometry`：归一化四角、范围/凸性/面积检查、输出像素预算。
- `DocumentCrop`：私有文件、原图保留、预览令牌、确认及输入元数据。
- `DocumentCropEditor`：SVG 四角/整框手势、键盘微调、预览和确认；原生 `dialog` 管理模态焦点，按钮触摸范围扩展。
- `RecognitionController`：确认前识别限制，重新裁剪后清除旧终态快照，识别结果另存。
- Maven Central 固定 `org.opencv:opencv:4.12.0`。APK 静态检查核对共享 `libc++` 为当前 MNN 使用的 NDK 28.1.13356709 运行库，包含 OpenCV 许可。

参考：[zynkware SDK](https://github.com/zynkware/Document-Scanning-Android-SDK) 的扫描/编辑流程；[OpenCV 官方 Android 文档](https://docs.opencv.org/4.13.0/d5/df8/tutorial_dev_with_OCV_on_Android.html) 说明 Maven 集成与本地初始化。此次保留 Camera2，不接入参考 SDK 的另一套相机活动。OpenCV 增加了 APK 体积，两版发布包约 50.8 MB；模型仍不随 APK 打包。

## 已执行验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 两版 assemble / JVM / lint | 0.7.6 每版 68 测试，0 失败/错误/跳过；lint 0 错误、11 项既有警告 | [checks.json](checks.json)、[发布构建日志](release-build.log)；先前开发构建见 [gradle-checks.log](gradle-checks.log) |
| 生产 Java 图像算法 | 6 类检查通过；实际文档图片加入倾斜及背景杂物；无边界回退、低对比度阈值轮廓、全图像素保持 | [algorithm.json](algorithm/algorithm.json)、[测试场景](algorithm/tilted-with-clutter.png)、[实际裁剪](algorithm/cropped.png) |
| 裁剪界面 | 4 组流程通过，含四角/整框移动、取消/历史、横屏/全图、确认令牌及重拍 | [ui.json](ui.json)、[竖屏](crop-portrait.png)、[横屏预览](crop-landscape-preview.png) |
| 现有界面回归 | 首页/图片/表格/公式/校对/导出/历史/Office、识别流程、导航竞态和流式渲染通过 | `regression-*.log` |
| APK 静态检查 | arm64、公共 C ABI/JNI、依赖闭合、无权重、原生 Release/-O3/NDEBUG、OpenCV/许可、当前 NDK libc++ | [apk.json](apk.json) |
| 签名兼容 | 两版均与 v0.7.5 的证书一致；摘要与实际 APK 关联 | [signing.json](signing.json) |

图像算法验证运行同一 `CropGeometry`／`DocumentBoundaryDetector` Java 文件，使用 `org.openpnp:opencv:4.9.0-0` 的 Linux 原生运行库。测试运行库 SHA 记录于 [runtime-sha256.txt](algorithm/runtime-sha256.txt)；Android 使用固定 4.12.0。浏览器使用模拟 Android 桥和上述真实像素裁剪产物，不声称是手机相机或 WebView 实测。

复跑命令：

```bash
python3 tools/verify_document_crop.py --opencv-jar /tmp/android-ocr-opencv-host-4.9.0.jar
node web/verify-document-crop.mjs
./gradlew testUserDebugUnitTest testLabDebugUnitTest assembleUserDebug assembleLabDebug lintUserDebug lintLabDebug
python3 tools/verify_ocr_apk.py --require-native-release --require-document-crop --output verification/document-crop/apk.json
```

## 真机待验收

当前 `adb devices -l` 没有设备。Titan_1 仍需复测拍摄方向、自动边界（背景杂物/倾斜/低对比度）、四角与整框触摸、返回/重拍/历史、真实 OCR 使用裁剪输入、连续识别及内存占用。未宣称裁剪检测率或 OCR 准确率指标。

## 发布 APK

本地固定副本位于项目 `artifacts/document-crop/release/`：`suishou-ocr-0.7.6-arm64.apk`、`suishou-ocr-0.7.6-lab-arm64.apk` 和 `SHA256SUMS.txt`。应用标识和签名兼容既有安装；旧的本地 BUG-005 测试副本不作为本次发布资产。

## GitHub 发布与下载复核

- [v0.7.6 预发布](https://github.com/bestkojima/suishou-ocr-android/releases/tag/v0.7.6)，发布时间 2026-10-09 15:55:59（Asia/Shanghai），源码提交 `8388588ba2851429dec322e8a4365557b33400af`；`main` 与 v0.7.6 标签已推送。
- [普通版 APK](https://github.com/bestkojima/suishou-ocr-android/releases/download/v0.7.6/suishou-ocr-0.7.6-arm64.apk)：50,771,217 字节，SHA-256 `277442d1cf47d6d316be7bf99cfc85aa95ff9ca180a6f6769012038db10ae381`。
- [测试版 APK](https://github.com/bestkojima/suishou-ocr-android/releases/download/v0.7.6/suishou-ocr-0.7.6-lab-arm64.apk)：50,771,233 字节，SHA-256 `870543693c49ffa3fa437af1c7b663d2b834c6e20b7e5e95dd6282b2c9f8afc7`。
- 两版及 SHA256SUMS.txt 从 GitHub 重新下载，文件大小与 SHA 均与本地及 GitHub 资产摘要一致；公开发布说明与 [release-notes.md](release-notes.md) 一致。结果见 [publication.json](publication.json)。
- v0.7.5、v0.7.4 及各自三项资产仍保留。BUG-001～004 仍待处理，真机待验收范围已写入发布说明。
