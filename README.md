# 随手识别 · Suishou OCR Android

Android 本地离线文档识别应用：拍照或导入图片，使用 PP-DocLayoutV3 检测版面，使用 OvisOCR2 或 GLM-OCR 识别文字／表格／公式，模型输出实时显示，并支持校对、历史记录与导出。

当前版本：**0.7.7-ocr**（`versionCode=14`，预发布）。Java + Camera2 + JNI/C++ + MNN，界面使用本地 WebView、React、Streamdown 和 KaTeX。APK不包含模型，首次需要在应用内下载；模型下载完成后，单图识别和结果阅读可离线运行。

0.7.7 新增模型目录配置驱动的 GLM-OCR 适配与模型切换，Ovis 保持默认，完整保留版面检测和真实流式输出。详见 [GLM-OCR 交付记录](verification/glm-ocr/DELIVERY.md)。

0.7.6 实现 BUG-005：拍照后先自动检测文档边界，支持四角/整框调整、透视裁剪预览和确认后识别。原照片保留，检测失败可手动调整；真机效果待验收。详见 [裁剪交付记录](verification/document-crop/DELIVERY.md)。

## 下载与安装

从 [GitHub Releases](https://github.com/bestkojima/suishou-ocr-android/releases/tag/v0.7.7) 下载：

- `suishou-ocr-0.7.7-arm64.apk`：普通版，应用ID `cn.local.ocr`。
- `suishou-ocr-0.7.7-lab-arm64.apk`：测试版，应用ID `cn.local.ocr.test`，可与普通版同时安装。
- `SHA256SUMS.txt`：安装包校验值。

需要 **Android 8.0 / API 26或以上、arm64-v8a**，及支持现代JavaScript的Android System WebView（Chromium 100+）。本次为预发布，使用开发签名的Debug APK，原生 MNN／docprase／JNI 使用 Release／`-O3`／`NDEBUG`；覆盖安装同类型版本可保留已下载模型和记录。普通版没有测试开关或WebView调试。

首次使用：打开设置 → 识别模型 → 选择 OvisOCR2 或 GLM-OCR → 浏览仓库文件，下载必需的配置、词表与权重 → 加载识别模型 → 拍照或导入图片。下载成功的自定义模型不一定与引擎兼容。

## 当前能力

| 功能 | 行为 |
| --- | --- |
| 拍照裁剪 | 自动检测文档边界，四角/整框调整，透视预览，确认后识别；保留原照片 |
| 单图真实OCR | 本地版面检测，逐区域识别文字、表格、公式；插图随结果保留 |
| 实时输出 | 正文来自实际模型生成流；JSON示例另有明确的回放模式 |
| 图片分辨率 | 均衡默认≤800万像素、长边≤4096；快速≤400万像素、长边≤2560；原图不缩小；保留原始文件 |
| 连续识别 | 复用已加载模型；默认最多4个CPU线程，可选1／2／4；区域会话隔离 |
| 校对与历史 | 修改Markdown、调整已完成区域顺序、保留阅读位置、重新识别另存记录 |
| 导出 | TXT、Markdown、含图片与DocumentIR的ZIP，系统保存／分享 |
| PDF与Office | 提取已有文档内容；待OCR图片明确标记，尚未接通完整PDF／Office页面OCR |
| 模型管理 | ModelScope仓库管理、选择下载、断点续传、下载完成时SHA-256校验 |

0.7.6 拍照先经过文档裁剪确认，再进入现有 OCR 流程；导入图片沿用原流程：

```text
拍照：保留原照片 → 整页分辨率策略／EXIF校正 → 自动边界／手动调整
  → 透视裁剪预览 → 确认同一 PNG 作为识别输入
导入：保留原文件 → 整页分辨率策略／EXIF校正 → source.png
共同识别流程：
  → 等比例缩放／补白到800×800做版面检测
  → 框映射回实际识别输入 → 裁剪各区域
  → 每个区域单独smartresize／补白／32对齐
  → OvisOCR2／GLM-OCR 真实生成、实时显示 → 保存DocumentIR与资源
```

默认区域视觉预算为65,536～313,600像素，不是所有区域统一变成560×560。均衡模式的裁剪来自降采样后的整页；密集小字可选原图重新识别。首次初始化与连续识别的耗时不同。

自 0.7.5 起固定关闭 `use_mmap` 和 `kvcache_mmap`，0.7.7 保持该设置。加载前设置有效配置，使用现有私有临时目录；不创建或复用 `ovis-mmap-*` 权重缓存。模型仍在应用内加载并本地执行。

## 模型来源

- [PP-DocLayoutV3-mnn](https://modelscope.cn/models/dr3334/PP-DocLayoutV3-mnn)
- [ovrics-ocrv2_mnn](https://modelscope.cn/models/dr3334/ovrics-ocrv2_mnn)

v0.7.7 新增 [GLM-OCR-MNN](https://modelscope.cn/models/MNN/GLM-OCR-MNN) 与模型选择入口：由模型目录 config 字段适配提示词、模板、图像 token、采样及工件，Ovis 仍为默认。配置见 [模型配置说明](docs/MODEL_CONFIG.md)，真实对照与发布证据见 [GLM-OCR 交付记录](verification/glm-ocr/DELIVERY.md)。

固定工件清单在 `ocr/models.json`（Ovis）和 `ocr/glm-models.json`（GLM）。SHA 在下载完成时校验；加载不重新扫描权重 SHA。

## 从源码构建

环境：JDK17、Node.js22、Git、Python3、Android SDK35、NDK28.1.13356709、CMake3.31.6。Gradle Wrapper与npm lockfile随仓库提供。

```bash
git clone https://github.com/bestkojima/suishou-ocr-android.git
cd suishou-ocr-android
python tools/setup_native_deps.py
npm ci
```

`native/docprase`包含实际构建源码快照；脚本从GitHub获取固定提交的MNN到`native/MNN`。已有目录版本不匹配或受跟踪文件有改动时，脚本保留该目录并报错。来源与文件校验记录见 [原生依赖](native/README.md)。不需要单独克隆另一个docprase仓库，也不需要在构建机下载模型权重。

Gradle 的 APK build type 与原生编译类型分开：`userDebug`／`labDebug` 仍可用开发签名覆盖安装，原生代码固定为 Release；以 CMakeCache 和实际编译命令验收优化，不以 APK 名称或符号是否被 strip 判断。

可用Android Studio打开根目录，设置SDK位置、安装上述NDK／CMake并选择`userDebug`或`labDebug`构建；也可以命令行构建。Windows使用`gradlew.bat`，Linux/macOS使用`./gradlew`；SDK位置保存在不提交的`local.properties`，或通过`ANDROID_HOME`配置。

```bash
./gradlew assembleUserDebug assembleLabDebug
./gradlew testUserDebugUnitTest testLabDebugUnitTest lintUserDebug lintLabDebug
npm run build:web
npm run test:web
python tools/verify_ocr_apk.py --require-native-release --require-document-crop --output verification/document-crop/apk.json
```

输出在`app/build/outputs/apk/{user,lab}/debug/`。浏览器预览使用`npm run preview`；其中相机、下载和真实模型推理需要APK。

连接Android设备、启用USB调试后可用ADB：

```bash
adb devices
adb install -r app/build/outputs/apk/user/debug/app-user-debug.apk
adb logcat -s OcrEngine
```

## 验证与当前边界

0.7.7 两版 JVM 各 74 项通过，lint 各 0 错误；GLM 配置、模型切换和真实流验证通过。用户扫描页 Linux 17 个区域正常停止，低像素仍错识，BUG-002 未关闭。完整证据见 [GLM-OCR 交付记录](verification/glm-ocr/DELIVERY.md)。

0.7.6 两版 JVM 各 68 项通过，lint 各 0 错误、11 项既有警告；裁剪界面 4 组和既有界面/识别/导航/流式回归通过，APK 通过原生 Release、arm64、OpenCV、依赖及签名检查。同一生产 Java 图像算法在 Linux 上验证倾斜、背景杂物、低对比度、无边界和全图输入。**Android设备的裁剪交互、推理、相机、Bitmap、性能与内存仍待实测**，JVM／浏览器／Linux 通过不能代替设备验收。

BUG-001 插图流式显示、BUG-002 重复生成、BUG-003 相机预览范围和 BUG-004 错误 LaTeX 的校对问题仍待处理，见 [Bug 记录](bugdoc.md)。历史 0.7.5 的真实模型输出、流式采集和分辨率验证保留在原交付记录中。

合成大图分辨率对比中，连续识别2208万像素20.67s→约800万像素12.87s；该数据不代表平板速度，也不是密集小字精度保证。模型可能给出不完整或未校验结果，界面会保留可用内容并标示。

- [0.7.5原生优化／关闭mmap与发布验证](verification/native-release/DELIVERY.md)
- [0.7.7 GLM-OCR 适配与发布验证](verification/glm-ocr/DELIVERY.md)
- [0.7.6拍照裁剪与发布验证](verification/document-crop/DELIVERY.md)
- [当前OCR实现与接口](docs/REAL_OCR.md)
- [0.7.4分辨率验证](verification/image-resolution/DELIVERY.md)
- [引擎复用与线程对比](verification/ocr-speed/DELIVERY.md)
- [真实流式输出证据](verification/live-ocr/DELIVERY.md)
- [发布记录](verification/github-release/DELIVERY.md)
- [早期架构文档](docs/DEVELOPMENT.md)（0.6.0历史基线，以当前OCR说明为准）
- [第三方组件与许可证](THIRD_PARTY_NOTICES.md)

本项目任务保存在`.scratch/`，领域说明为`CONTEXT.md`与`docs/adr/`。
