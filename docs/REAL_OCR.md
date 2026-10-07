# 单图真实 OCR · 0.7.4

实施规格与任务：[本地规格](../.scratch/android-real-ocr/spec.md)。已执行的分项证据和设备待办：[交付记录](../verification/real-ocr/DELIVERY.md)。

## 两个项目与最小接入范围

Android 项目保留 Java 相机、导入器、模型下载和 DocumentStore，前端保留 React/Streamdown、回放 reducer、校对、排序和导出。相邻 docprase 的 `dococr_c` 公共 C ABI 已有 PP-DocLayoutV3、区域规划、归属与阅读顺序、OvisOCR2、JSON/Markdown/资源导出及安全取消；发布版使用 native/docprase 中的实际源码快照构建，MNN 由工具获取固定提交。本轮没有修改 docprase 或 MNN 工作区中的源码和已有未提交改动。

新增模块：`NativeOcr` 与 JNI 为薄适配，`RecognitionController` 管理应用级单作业，`RecognitionModels` 绑定固定工件，`ImageInput`/`ImagePixels` 将 EXIF 1～8 规范为已旋转/镜像的 PNG。规范图片先写同目录临时文件，同步成功后原子替换；失败不留下阻断重试的 PNG，现有原始文件不被覆盖。Android 的 PDF OCR 返回明确的 unsupported 错误，PDF/Office 原生解析继续走已有 Java 实现。

## 构建与模型

先运行 `python tools/setup_native_deps.py` 获取固定 MNN，docprase 源码已随仓库放在 `native/docprase`；还需要 Android SDK 35、NDK `28.1.13356709`、CMake `3.31.6`、JDK 17 和现有 npm 依赖：

```bash
npm run build:web
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleUserDebug assembleLabDebug
python3 tools/verify_ocr_apk.py
```

`app/src/main/cpp/CMakeLists.txt` 可配置 `DOCOCR_SOURCE_ROOT` 和 `MNN_SOURCE_ROOT`。默认从仓库内 native 依赖构建，MNN 分库；合并库时将 LLM 绑定为 MNN target，避免查找到宿主 Linux 库。MNN Android command 的 Express 输出目录在 App CMake 中修正，保证 Gradle 收集依赖。分库构建已执行；合并库分支尚未独立构建验收。

支持工件清单在 `app/src/main/assets/ocr/models.json`，生产配置在 `ocr/config.json`。模型根目录直接指向现有私有 `models/{repo}/files`。SHA-256 在下载完成时校验，校验成功并将文件移到最终路径后记录大小、修改时间与校验值。加载仅检查九个必需文件的存在性、大小及匹配的已校验下载记录；新记录还检查修改时间，旧版 `verified` 记录保留兼容。未完成下载、仅大小校验、工件版本不匹配或已变化文件不能激活。需要重新校验时，在下载页选择对应文件并点击“下载 / 继续所选文件”，完整本地文件会在下载流程中校验并复用，不在加载阶段扫描权重。

根据用户 2026-10-07 的后续要求，Android 加载不重复计算文件 SHA。`android_engine_sources.cmake` 为本平台生成 config／版面／识别／ABI 四份适配源码，移除各加载路径的文件散列扫描，保留固定工件和模型配置匹配；相邻 docprase 与 MNN 源码不变。替换必须精确匹配，发现源引擎变更或残留散列调用时构建失败并要求核对。

Ovis 初始化参照 MNN Chat 的 `LlmSession::Load`，执行 `createLLM → set_config → load`，启用 `use_mmap` 并传入私有可写 `tmp_path`，缓存按模型／配置身份隔离。这是 MNN 的权重运行缓存，会占用额外磁盘空间；首次建立与后续复用的耗时分别记录。临时配置和裁剪仍使用 App 缓存。0.7.3 的CPU线程默认自动选择最多4个，并受可用处理器数限制，可在模型页选择1／2／4。`OcrEngine` 日志记录下载状态检查、生产引擎总加载及 Ovis 初始化耗时。下载完成、文件就绪和实际加载仍是独立状态；自定义公开仓库下载不承诺推理兼容。

## 0.7.4 图片输入分辨率

之前版面张量已固定为 800×800，Ovis 区域输入默认按比例缩放／补白至 65,536～313,600 像素，按 32 对齐（长边不一定是 560）；仅缺少视觉 token 时的定向重试可提高区域预算。Android 导入原图却一直完整解码、分配像素数组、做 EXIF 和保存全分辨率 PNG。这会增加大照片的解码、拷贝、压缩、native 页面处理成本，也会让更多区域接近视觉预算上限。

设置中的“图片分辨率”提供：均衡（默认长边≤4096、≤800万像素）、快速（长边≤2560、≤400万像素）、原图（不缩小）。小图不放大；继续拒绝超过6400万像素的源图片。先读 bounds，选不低于目标尺寸的最大 2 次幂 `inSampleSize`，再精确缩放，最后校正 EXIF；方向正常时省掉两份像素数组。参考 [Android 大图解码说明](https://developer.android.com/topic/performance/graphics/load-bitmap)。高分辨率上限不是识别精度保证，小字文档可选原图重新识别。

保留 `rawInput`；`imagePreparation` 记录版本、策略、原始尺寸、EXIF、预采样、实际解码／输入尺寸与总处理耗时（`OcrEngine` 日志）。同策略已准备输入直接复用，旧待识别记录会补做规范化。每项作业开始固定策略，设置变化对下次识别生效；重新识别另存记录，并复制原始文件，策略改变时从原始文件重建，避免多次缩小。缺少原文件的老记录只能使用保存的 source。PNG 输入、页面、资源尺寸与 native 输出的坐标同源，已完成的旧结果不被改写。

真实模型分辨率对比与 Android 待验收范围见 [本轮交付记录](../verification/image-resolution/DELIVERY.md)。

## 请求与事件

沿用 `window.AndroidHost.request`/`nativeReply`；新增请求：

| 请求 | 参数 | 结果 |
| --- | --- | --- |
| `recognize` | `id`, `again` | 已保存并关联新 jobId 的文档；活跃作业冲突时报错 |
| `recognitionStatus` | `id` | 文档所属作业状态、sequence、原生区域进度和真实模型正文快照 |
| `cancelRecognition` | `id`, `jobId` | 提交前进入取消中；结果已保存时保持当前结束状态，等待清理 |
| `setOcrThreads` | `threads`: 0（自动）／1／2／4 | 空闲时保存设置；实际线程变化先卸载引擎；活跃作业与下载时拒绝更改 |
| `activateModels` | 无 | 后台检查已校验下载记录并实际加载，不重新计算文件 SHA；由 `models.readiness` 读取状态 |

`nativeEvent('recognition', state)` 与状态轮询使用同一 jobId/docId/sequence 归属；旧作业、低序号事件和切换文档后的响应不能覆盖当前页面。状态/取消从桥接入口直接处理，原生同步运行使用独立 executor。JNI 状态同时读取真实事件与模型正文累计快照，正文来自 MNN 的生成输出流。

状态转换及实际阶段／区域进度变化时递增 `sequence`；进程退出后的恢复终态使用高于已保存状态的序号并落盘，后续查询保持该序号。前端忽略同作业的重复或较低序号，在 React 状态提交及异步结果读取返回时再次核对归属和序号，避免连续到达的乱序事件覆盖较新状态。ticket2 补充验证见 [验收记录](../verification/ticket2/DELIVERY.md)。

启动识别请求记录发起时的文档和导航次数；回复到达时，只有仍在该次打开的页面才应用返回文档或显示错误。返回首页、切换文档或重开同一文档之后，旧启动回复不会打开旧页面或覆盖恢复的新进度，已启动的原生作业继续运行。启动等待期间按文档维护全部待回复请求并禁用重复提交；同页迟到的启动快照在 React 状态提交时保留同 jobId 较高 sequence 的进度，取消请求的迟到错误也按导航归属处理。ticket4 补充证据见 [交付记录](../verification/ticket4/DELIVERY.md)。

状态为 `waiting`、`preparing`、`missing-models`、`recognizing`、`saving`、`cancelling`、`succeeded`、`partial`、`blank`、`failed`、`cancelled`。推理期间返回首页只暂停展示；不会取消识别。进程退出不继续推理；重开时，未提交结果的遗留运行状态转为可重试失败，已提交的真实结果恢复保存的成功／partial／空白终态。安全清理失败时保持作业占用并提示重启，避免复用尚存活的资源。

## 结果与校对

先写暂存目录的完整 DocumentIR、Markdown、run-manifest 和全部资源，再通过现有 normalize/storage 流程保存 `real-ocr` 文档。提交后设置 `resultSaved` 并禁用取消，原生作业资源清理结束后发布最终状态；本次流式识别的成功结果直接完整显示，用户可手动回放；没有有效正文的取消不调用结果适配。DocumentIR 的原始元数据不被扁平展示区域覆盖，partial 区域保留状态和提示。无展示区域且引擎明确提供 `empty_page` 证据时显示为空白；原生记录仍保留原始状态。

校对继续保存为 `edits`，同页排序修改展示顺序及导出副本的 reading_order。ZIP 包含原始 `original-document.json`、当前阅读顺序的 `document.json`、校对后 Markdown、App 状态、全部声明资源（包括 `assets/` 外的合法路径）和全部原生 assets；同一资源只打包一次，再次导入恢复校对。多次导入／导出仍保留首次原始 DocumentIR，不以排序后的副本替换。重新识别复制输入生成新文档，不迁移旧校对。

真实结果和 JSON/ZIP 导入共用完整／partial／空白判定：页面单独标记 partial 也提示部分成功；空白需要明确的 `empty_page` 或 blank 证据。浏览器预览导出同时保留二进制资源、运行元数据和页面原图的本地路径，渲染器支持已声明的合法本地图片路径。ticket3 补充修复、回归和证据见 [交付记录](../verification/ticket3/DELIVERY.md)。

## 验证入口

```bash
./gradlew testUserDebugUnitTest testLabDebugUnitTest
npm run test:web
node web/verify-models.mjs
node web/verify-reorder.mjs
node web/verify-editor-drag.mjs
node web/verify-recognition.mjs
node web/verify-recognition-navigation.mjs
python3 tools/verify_real_ocr.py
python3 tools/package_real_preview.py
node web/verify-real-results.mjs
```

最后三个脚本依赖本轮 Linux 引擎/模型和实际输出。`verify_real_ocr.py` 执行公共生产 C ABI 的真实取消、BUSY、同引擎恢复和重复/空白输入；界面测试用实际输出 ZIP。控制器 JVM 补充检查使用固定内部状态，断言公开取消／查询结果和持久化终态；不代表 AndroidHost 自动流程或 native 清理时序已在设备执行。partial 预览明确使用既有 Linux 真实样本和 provenance，不把历史样本当作本轮新推理。

桌面生命周期证据可用 `python3 tools/verify_real_ocr.py --output verification/ticket4/desktop` 独立保存，保留此前交付输出。导航专项仅替换 AndroidHost 宿主，通过公开请求／事件和界面操作验证迟到回复；JVM 的失败后重试使用不可用模型资源场景，不执行 JNI。

没有连接 Android 设备，因此实际 APK 加载、首图/重复推理、相机方向、内存/耗时、导航/取消及系统进程退出后的重试等仍待实机验收；本轮 CPU 桌面内存与耗时不代表设备性能。

ticket5 在 ticket3／ticket4 修复之后重新构建两版 APK、执行生产 C ABI 识别并用该次结果生成预览，完整分项证据与设备清单见 [ticket5 交付记录](../verification/ticket5/DELIVERY.md)。预览脚本通过 `OCR_RESULT_DIR` 选择真实输出目录，`OCR_EVIDENCE_DIR` 选择报告／截图目录，报告记录导入 ZIP 的路径与 SHA-256；不要仅更换截图目录后声称输入已更新。`verify_real_ocr.py --input <图片> --output <新的空目录>` 会验证取消与恢复后完整输出、重复输入和空白页；`package_real_preview.py --output <该目录>` 生成预览 ZIP；`verify_ocr_apk.py --output <报告路径>` 保存独立静态检查。partial 使用带 provenance 的既有真实样本，并与新推理分开注明。

浏览器识别桥接新增连续下载准备、实际加载状态、识别结果保存、另存重新识别和旧校对导出请求场景。下载／加载／正文与导出宿主均为明确模拟，实际文件导出语义另由真实输出往返和 JVM 测试验证。拖动校对与识别桥接脚本共用 4196 端口，运行时应串行执行。

## 0.7.1 输入解码修复

安卓实测报告 `engine_ready:true` 和 `decode/input_error/invalid input` 后，实际 JNI 最小复现确认了 Alpha PNG 的兼容缺陷：Android ARGB_8888 规范 PNG 含 4 通道，而引擎的编码 PNG 契约仅接受灰度／RGB。JNI 现在将 Alpha PNG 以白纸合成为 RGB8 后提交公共 C ABI，兼容之前已保存的原记录。两版 APK 更新为 `0.7.1-ocr`；覆盖安装同类型 APK 后可直接重试，无需重新下载模型。回归脚本 `tools/verify_android_image_input.py` 通过实际 JNI 验证通道及输出像素，`--production` 模式另行验证真实模型；[修复记录](../verification/android-image-decode/DELIVERY.md) 区分桌面结果与设备待验收。


## 0.7.2 真实模型流式输出

旧链路在 Ovis 的 `response` 外使用 `ostringstream`，区域结束后才返回正文，页面在整页保存后逐字回放结果。现在 `AndroidGenerationStream` 在真实输出流收到字节时更新作业／区域累计快照，JNI 的 `status` 携带 `stream`，控制器仅在 revision 变化时递增 sequence；活跃页面约每 150ms 轮询。没有人工补字或按 JSON 字符数生成正文。正常首内容仍需等待该区域视觉处理与 prefill 完成。

累计正文与进度事件队列分开，轮询慢或返回页面不会丢失已有字节。区域重试增加 attempt 并替换原文，末尾不完整 UTF-8 字符留待下一次字节补齐。JNI 使用标准 UTF-8 构造 Java String，兼容四字节字符。流模块在作业销毁时清理，实时正文不反复落盘；最终完整 DocumentIR／资源保持原有保存流程。

文字增量由 Streamdown 渲染；未闭合的表格、公式或数学表达式显示实时原文，区域完成后排版。临时区域不能校对、排序或导出；整页保存后替换为最终展示区域与资源，直接显示完整结果。JSON 示例和用户主动选择的结果回放继续使用既有回放逻辑，设置中的字符数已明确标为 JSON 回放参数。

0.7.2 的固定配置为 CPU 单线程、区域串行；GPU 后端未编译启用。状态快照与 `OcrEngine` 日志新增首内容、视觉、prefill、decode、输出 token 和区域总耗时。本轮没有凭桌面计时改动平板默认线程数或开启 GPU。流式正确性与速度分析见 [交付记录](../verification/live-ocr/DELIVERY.md)；平板速度仍需真机实测。

```bash
node web/verify-live-recognition.mjs
python3 tools/verify_live_ocr.py --library <同源流式适配的Linux库> --output <新证据目录>
python3 tools/verify_recognition_stream.py --library <同源流式适配的Linux库>
python3 tools/verify_android_image_input.py --production --require-stream --library <同源流式适配的Linux库> --output <新JNI证据目录>
```

专项浏览器检查使用模拟桥及 `verification/live-ocr/host/timeline.json` 中实际模型采集的快照；Linux JNI 检查使用 App 的真实 JNI 源码与同源模型，均不等同于 Android 设备验收。覆盖安装同类型 `0.7.2-ocr` APK 保留下载模型，不需要重新下载。


## 0.7.3 连续识别速度与模型复用

区域之间调用 `Llm::reset()` 清空会话／KV，不读取或重新加载权重。0.7.2 每张图完成后销毁引擎，下次又创建；0.7.3 只销毁本图作业和结果，保留引擎及已准备的运行缓存，下一张直接复用。区域之间仍隔离上下文，不跨区域复用不同图片的KV，也不并行运行同一不支持独立会话的后端。

App 的实际配置在 `RecognitionModels.config()` 中设置 CPU 线程；自动选择1／2／4，最多4并受可用CPU限制。模型页可手动选择。加载后的引擎记录实际线程数；自动与显式设置产生相同有效线程时保留引擎，不重复加载。真正的线程变化、模型下载／删除先卸载空闲引擎。Android 的内存压力通知及退出 Activity 时通过同一推理 executor 请求卸载，避免与运行中的区域并发销毁；卸载失败保留占用并提示重启。

Android 适配同时生成 `abi.cpp`，将计划线程数传到嵌套版面／语言／视觉后端；后端错误恢复重建也使用原计划。manifest 的有效线程、`layout_threads`、`ovis_threads` 和 `use_mmap` 按实际配置记录，profile 与流式计时也记录线程。原生公共 ABI 和邻仓源码不变。

顺序桌面对比中，重复样图第二次识别为单线程22.03s、两线程14.63s、四线程11.22s，Markdown完全一致。四线程新引擎首次识别仍约23s，首内容等待更长；单独保留视觉单线程没有改善首次等待，连续识别也更慢，所以未采用。最终代码复验连续两次为12.80s和11.20s。以上是同源 Linux VM 的测量，不代表平板速度；本轮没有减少分辨率、token预算或更换模型。详细记录和设备待办见 [交付记录](../verification/ocr-speed/DELIVERY.md)。

```bash
python3 tools/benchmark_ocr.py --library <同源适配的Linux库> --threads 4 --runs 3 --output <新证据目录>
./gradlew testUserDebugUnitTest testLabDebugUnitTest
node web/verify-models.mjs
```

覆盖安装同类型 `0.7.4-ocr` 保留下载模型，随后在模型页选择自动、1、2或4比较设备耗时。首次切换线程的模型缓存按配置身份隔离，首次运行与连续识别必须分别测量。GPU后端仍未启用；需要真机驱动和完整质量／性能对比才能判断其收益。
