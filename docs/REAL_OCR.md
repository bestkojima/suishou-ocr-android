# 单图真实 OCR · 0.7.0

实施规格与任务：[本地规格](../.scratch/android-real-ocr/spec.md)。已执行的分项证据和设备待办：[交付记录](../verification/real-ocr/DELIVERY.md)。

## 两个项目与最小接入范围

Android 项目保留 Java 相机、导入器、模型下载和 DocumentStore，前端保留 React/Streamdown、回放 reducer、校对、排序和导出。相邻 docprase 的 `dococr_c` 公共 C ABI 已有 PP-DocLayoutV3、区域规划、归属与阅读顺序、OvisOCR2、JSON/Markdown/资源导出及安全取消；接入直接编译其生产源代码。本轮没有修改 docprase 或 MNN 工作区中的源码和已有未提交改动。

新增模块：`NativeOcr` 与 JNI 为薄适配，`RecognitionController` 管理应用级单作业，`RecognitionModels` 绑定固定工件，`ImageInput`/`ImagePixels` 将 EXIF 1～8 规范为已旋转/镜像的 PNG。规范图片先写同目录临时文件，同步成功后原子替换；失败不留下阻断重试的 PNG，现有原始文件不被覆盖。Android 的 PDF OCR 返回明确的 unsupported 错误，PDF/Office 原生解析继续走已有 Java 实现。

## 构建与模型

需要相邻的 `../docprase`、`../MNN` 源码、Android SDK 35、NDK `28.1.13356709`、CMake `3.31.6`、JDK 17 和现有 npm 依赖：

```bash
npm run build:web
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleUserDebug assembleLabDebug
python3 tools/verify_ocr_apk.py
```

`app/src/main/cpp/CMakeLists.txt` 可配置 `DOCOCR_SOURCE_ROOT` 和 `MNN_SOURCE_ROOT`。默认 MNN 分库；合并库时将 LLM 绑定为 MNN target，避免查找到宿主 Linux 库。MNN Android command 的 Express 输出目录在 App CMake 中修正，保证 Gradle 收集依赖。分库构建已执行；合并库分支尚未独立构建验收。

支持工件清单在 `app/src/main/assets/ocr/models.json`，生产配置在 `ocr/config.json`。模型根目录直接指向现有私有 `models/{repo}/files`，不复制权重。九个文件全部按存在性、固定大小和 SHA-256 核验后调用真实 `dococr_create`；下载完成、文件就绪和实际加载是独立状态。临时配置和裁剪使用 App 可写缓存，CPU 单线程。自定义公开仓库下载仍可用，不承诺推理兼容。

## 请求与事件

沿用 `window.AndroidHost.request`/`nativeReply`；新增请求：

| 请求 | 参数 | 结果 |
| --- | --- | --- |
| `recognize` | `id`, `again` | 已保存并关联新 jobId 的文档；活跃作业冲突时报错 |
| `recognitionStatus` | `id` | 文档所属作业状态、sequence 和原生区域进度 |
| `cancelRecognition` | `id`, `jobId` | 提交前进入取消中；结果已保存时保持当前结束状态，等待清理 |
| `activateModels` | 无 | 后台完整核验与实际加载；由 `models.readiness` 读取状态 |

`nativeEvent('recognition', state)` 与状态轮询使用同一 jobId/docId/sequence 归属；旧作业、低序号事件和切换文档后的响应不能覆盖当前页面。状态/取消从桥接入口直接处理，原生同步运行使用独立 executor。JNI 状态同时读取真实事件与快照，不模拟模型正文增量。

状态为 `waiting`、`preparing`、`missing-models`、`recognizing`、`saving`、`cancelling`、`succeeded`、`partial`、`blank`、`failed`、`cancelled`。推理期间返回首页只暂停展示；不会取消识别。进程退出不继续推理；重开时，未提交结果的遗留运行状态转为可重试失败，已提交的真实结果恢复保存的成功／partial／空白终态。安全清理失败时保持作业占用并提示重启，避免复用尚存活的资源。

## 结果与校对

先写暂存目录的完整 DocumentIR、Markdown、run-manifest 和全部资源，再通过现有 normalize/storage 流程保存 `real-ocr` 文档。提交后设置 `resultSaved` 并禁用取消，原生资源清理结束后发布最终状态；成功结果进入回放；没有有效正文的取消不调用结果适配。DocumentIR 的原始元数据不被扁平展示区域覆盖，partial 区域保留状态和提示。无展示区域且引擎明确提供 `empty_page` 证据时显示为空白；原生记录仍保留原始状态。

校对继续保存为 `edits`，同页排序修改展示顺序及导出副本的 reading_order。ZIP 包含原始 `original-document.json`、当前阅读顺序的 `document.json`、校对后 Markdown、App 状态、全部声明资源（包括 `assets/` 外的合法路径）和全部原生 assets；同一资源只打包一次，再次导入恢复校对。重新识别复制输入生成新文档，不迁移旧校对。

## 验证入口

```bash
./gradlew testUserDebugUnitTest testLabDebugUnitTest
npm run test:web
node web/verify-models.mjs
node web/verify-reorder.mjs
node web/verify-editor-drag.mjs
node web/verify-recognition.mjs
python3 tools/verify_real_ocr.py
python3 tools/package_real_preview.py
node web/verify-real-results.mjs
```

最后三个脚本依赖本轮 Linux 引擎/模型和实际输出。`verify_real_ocr.py` 执行公共生产 C ABI 的真实取消、BUSY、同引擎恢复和重复/空白输入；界面测试用实际输出 ZIP。控制器 JVM 补充检查使用固定内部状态，断言公开取消／查询结果和持久化终态；不代表 AndroidHost 自动流程或 native 清理时序已在设备执行。partial 预览明确使用既有 Linux 真实样本和 provenance，不把历史样本当作本轮新推理。

没有连接 Android 设备，因此实际 APK 加载、首图/重复推理、相机方向、内存/耗时、导航/取消及系统进程退出后的重试等仍待实机验收；本轮 CPU 桌面内存与耗时不代表设备性能。
