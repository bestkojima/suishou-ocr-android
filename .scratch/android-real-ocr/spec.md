# Android 单图真实 OCR 融合规格

Status: ready-for-agent

## Problem Statement

随手识别已经能拍照、导入文档、下载模型、展示已有 DocumentIR、校对、调整阅读顺序并导出，但无法对用户新拍摄或导入的图片执行真实识别。保存图片、模型文件下载完成和已有结果回放，都不能满足用户希望在 App 内使用模型得到识别内容的目标。

相邻 docprase 已具备 PP-DocLayoutV3 与 OvisOCR2 的生产推理管线和公共 C ABI，但现有产物属于 Linux，尚未与 Android 的构建、模型目录、输入方向、作业生命周期和结果展示贯通。

用户最终希望安装运行 App 并看到新输入的真实识别结果。当前环境不能进行 APK 内推理验收，因此本轮必须分别交付 Android 构建、桌面真实推理和界面验证证据，并保留明确的设备验收事项。

## Solution

将 docprase 的生产推理能力接入现有 Android App。首轮仅支持 arm64-v8a 手机上的拍照和导入单张图片，模型在 App 内下载，APK 不包含模型权重。模型准备完成后，识别在设备本地离线运行，不依赖电脑或服务器。

采集输入后先保存原图，检查必需模型并自动开始真实识别；缺模型时保存为待识别输入，引导用户下载并提供稍后识别入口。识别期间展示真实阶段进度，完整结果落盘后沿用现有逐步展示、暂停展示、校对、排序、历史和导出功能。结果回放必须与真实识别阶段区分。

一次只运行一个识别作业。返回首页不取消作业；显式取消等待安全停止；失败保留输入并可重试。重新识别产生新记录，不覆盖旧识别结果和校对内容。部分成功结果应保留可用内容并明确标示未完成区域。

## User Stories

1. As an Android app user, I want to recognize a newly captured photo, so that I can obtain content from my own document.
2. As an Android app user, I want to recognize an imported image, so that I can use documents already stored on my phone.
3. As an Android app user, I want recognition to run locally after model preparation, so that I can use it without an inference server.
4. As an Android app user, I want to recognize images without a network connection once models are ready, so that recognition remains available offline.
5. As an Android app user, I want the APK to exclude model weights, so that installing the application does not require a model-sized installation package.
6. As an Android app user, I want to obtain the supported models through the existing download screen, so that I can prepare recognition inside the app.
7. As an Android app user, I want incomplete model downloads to be distinguished from recognition readiness, so that I know whether recognition can start.
8. As an Android app user, I want missing or incompatible model files to be identified before recognition, so that I can correct the problem.
9. As an Android app user, I want model loading failures to produce a useful error, so that I can act on the failure rather than wait indefinitely.
10. As an Android app user, I want photos and imported images saved before recognition starts, so that failures do not lose my input.
11. As an Android app user, I want missing models to leave my image available for later recognition, so that I do not need to capture or import it again.
12. As an Android app user, I want recognition to start automatically when models are ready, so that the capture-to-result workflow is direct.
13. As an Android app user, I want image orientation handled consistently, so that rotated camera images are recognized in the correct direction.
14. As an Android app user, I want to inspect the original image, so that I can compare it with recognized content.
15. As an Android app user, I want to see actual recognition stages, so that I can tell the engine is processing my image.
16. As an Android app user, I want preparation, recognition, result presentation and failure to be distinguishable, so that the app does not misrepresent its progress.
17. As an Android app user, I want recognition to avoid blocking the interface, so that I can continue using the app.
18. As an Android app user, I want a clear response when another recognition job is already running, so that I know why a second job has not started.
19. As an Android app user, I want recognition to continue when I return to the home screen, so that navigation does not discard my work.
20. As an Android app user, I want to return to a running document and see its current state, so that I can follow its progress.
21. As an Android app user, I want an explicit cancellation action, so that I can stop a recognition task I no longer need.
22. As an Android app user, I want cancellation to show that safe stopping is still in progress, so that I do not assume the engine has already stopped.
23. As an Android app user, I want a failed or cancelled task to retain its input, so that I can retry it later.
24. As an Android app user, I want to retry recognition after a failure, so that a temporary problem does not make the document unusable.
25. As an Android app user, I want saved input to remain available after the app process exits, so that I can restart recognition.
26. As an Android app user, I want obsolete job events to be ignored, so that an older job cannot replace my current document state.
27. As an Android app user, I want complete recognition output to use the existing result presentation, so that familiar reading controls remain available.
28. As an Android app user, I want pausing result presentation to affect presentation only, so that it is not confused with cancelling recognition.
29. As an Android app user, I want recognized text, formulas, tables and images to render appropriately, so that I can read structured document content.
30. As an Android app user, I want partial recognition to preserve usable content and mark incomplete regions, so that I can assess what needs correction.
31. As an Android app user, I want recognized content to follow its reading order, so that the document reads coherently.
32. As an Android app user, I want to correct recognized content, so that I can fix model errors before sharing it.
33. As an Android app user, I want to adjust the reading order of completed display regions, so that I can correct ordering errors.
34. As an Android app user, I want results and corrections to be saved in history, so that I can reopen them later.
35. As an Android app user, I want recognition results exported as text, Markdown or a document bundle, so that I can use them outside the app.
36. As an Android app user, I want original recognition output and my corrections to remain distinguishable, so that I can trace the source of exported content.
37. As an Android app user, I want repeated recognition saved as a new record, so that my previous result and corrections remain intact.
38. As an Android app user, I want models used by an active job protected from deletion, so that model management does not break recognition.
39. As an Android app user, I want existing PDF, Office and JSON/ZIP workflows to remain usable, so that adding image recognition does not remove existing features.
40. As an Android app user, I want custom model repository downloads to remain available without being advertised as automatically compatible, so that I understand the supported recognition setup.
41. As a project maintainer, I want an arm64-v8a APK containing the required native inference backend, so that the Android integration can be installed on a supported device.
42. As a project maintainer, I want a fresh real inference result from the same engine used by the integration, so that result adaptation is grounded in actual model output.
43. As a project maintainer, I want interface previews populated with real recognition output, so that I can inspect the intended result display in the current environment.
44. As a project maintainer, I want desktop, browser and Android verification reported separately, so that I can see which behavior has actually been verified.
45. As a project maintainer, I want an explicit pending device acceptance checklist, so that compiling an APK is not mistaken for successful on-device inference.

## Implementation Decisions

- 保留现有 App 和结果渲染器，在既有输入到文档的流程中新增真实识别能力。
- 首轮支持 arm64-v8a 的拍照和单张图片导入。模型准备完成后，推理在设备本地离线运行。
- 复用 docprase 的生产管线：PP-DocLayoutV3 版面分析、识别区域规划、内容归属、阅读顺序、OvisOCR2 识别及 DocumentIR/资源导出。已有 PP-OCR mobile 工件不替代这条生产管线。
- 用 Android NDK 构建原生引擎和 MNN 视觉/LLM 后端，新增 JNI 适配层；处理合并库与分库链接差异，确保 APK 实际启用了生产识别后端。
- 新增独立于结果页生命周期的原生识别控制层。同步推理在后台工作线程执行，状态读取与取消不能被该线程阻塞。
- 扩展现有 App 原生请求／事件桥，提供模型就绪、启动、状态和取消行为。作业具有稳定标识、关联文档及明确状态；事件归属于具体作业，旧事件不能改写其他文档。
- 区分输入已保存、缺少模型、准备/加载、识别中、取消中、成功、部分成功、失败和已取消。结果展示进度与模型推理状态分别记录。
- 一次只允许一个活跃识别作业。第二次启动请求应明确告知已有作业，不暗中排队或并行创建引擎执行。
- 推理前先保存原图，规范适用的 EXIF 旋转和镜像变换，使推理像素、原图显示及结果几何保持一致。
- 沿用现有两个默认 ModelScope 仓库，把已下载文件映射为引擎模型根目录，无需重复复制模型。权重不打包进入 APK。
- 模型契约为生产配置要求的一个版面工件及八个 Ovis 工件。激活前核对存在性、预期大小和固定 SHA，单独记录实际引擎加载结果；文件下载完成不等于模型可推理。
- 保留自定义公开仓库下载能力，不承诺任意下载模型自动兼容当前识别管线。
- 使用应用私有模型目录及可写缓存存放临时文件。首轮沿用现有 CPU 单线程后端配置。
- 模型就绪时，拍照/导入保存输入后自动识别；未就绪时保留待识别输入，显示缺模型状态并提供下载及稍后识别操作。
- 返回首页继续作业，再次打开文档读取当前作业状态；不承诺 Android 杀进程后的推理继续执行。
- 取消采用安全边界上的协作式停止。原生执行及清理进入终态前保持“取消中”，不销毁仍被推理使用的资源；当前区域生成可能结束后才停止。
- 失败或取消保留原始输入并可重试。原生 API 没有有效结果时，不将取消作业表述为已识别正文。
- 将完整 DocumentIR、Markdown 和引用图片资源落盘后，进入现有文档归一化与展示流程。保留支持的 schema 变体及原始元数据，不硬编码只接受一个版本。
- 区分部分成功、空白页与失败，保留 partial 区域状态和可用内容；API 返回成功不能理解为所有区域内容均正确。
- 推理阶段显示真实进度；最终结果保存后沿用既有回放控制。暂停展示不暂停模型，也不调用识别取消。
- 保留阅读顺序、稳定展示区域身份、原图查看、校对、排序、历史和导出。展示区域不假定与 LayoutBlock 或 Region 一一对应。
- 重新识别生成新文档记录，不覆盖旧记录及校对，也不自动把旧修改迁移到新区域标识。
- 活跃作业使用模型时阻止删除相关文件；删除后重新判断模型就绪状态，再允许下一作业。
- 保留 PDF/Office 原生内容提取及 JSON/ZIP 结果回放，它们的模型识别扩展不属于首轮。

## Testing Decisions

- 主要集成测试入口采用现有 App 原生请求／事件桥。通过公开请求和事件驱动识别场景，验证界面、文档状态、历史和导出，不断言私有辅助函数、内部队列形状或偶然时序。
- 受测模块包括模型就绪判定、识别作业控制、DocumentIR 适配与文档存储、结果展示及导出；从同一个公开入口观察这些模块协同后的结果，复用已有测试设施，不为各模块新增独立测试接口。
- 好的测试提供具体输入或用户操作，并检查可观察的成功、失败或取消结果。浏览器原生模拟验证桥接/UI 契约，不能报告为真实模型推理。
- 既有浏览器测试已在该边界替换原生宿主以验证模型下载与进度；沿用此方式覆盖模型就绪、自动启动、作业冲突、导航、阶段变化、取消、重试和旧事件隔离。
- 复用文档归一化、存储、阅读顺序及导出再导入测试，核验真实 DocumentIR 适配、partial 状态、资源引用、校对保留和导出语义。优先断言完整文档行为，不为每个适配辅助函数新建测试入口。
- 覆盖缺少任一必需模型文件、所选文件未配齐、大小/SHA 不符和引擎加载失败；不能因部分下载完成而显示模型就绪。
- EXIF 测试使用非对称图片，覆盖旋转及镜像，避免错误变换偶然通过；在输入输出边界验证方向和尺寸。
- 覆盖成功、部分成功、空白、失败、取消，以及重复/迟到事件、安全清理、重试、重新识别和旧校对保留。验证一次一个活跃作业，取消清理中不能启动第二项。
- 验证暂停回放只影响展示状态，取消识别改变作业状态且等待真实终态事件。
- 构建普通版和测试版 arm64-v8a APK，检查原生架构、必需库与公共符号，并确认生产识别后端已构建。打包检查不证明模型成功加载。
- 使用同一引擎及支持的模型工件执行一次新的桌面真实识别，记录配置/输入身份、退出或终态、可获得的耗时及内存、实际 JSON/Markdown/资源。用结果验证适配和预览，不作为 Android 性能承诺。
- 运行受影响的既有浏览器及 JVM 测试，覆盖阅读、历史、校对、排序、导出再导入和模型管理；不扩展到无关行为。
- 当前环境分项记录 Android 编译、桌面真实推理和浏览器展示证据，原生生命周期模拟单独标注。
- 本轮交付记录逐项列出 APK 产物、实际输入和真实识别输出、界面预览、受影响检查的结果及设备待验收项。每项注明执行环境和是否模拟，不能将几类证据合并标为“设备推理通过”。
- 有支持的 Android 设备后补齐真实模型加载、首图和重复推理、EXIF、内存与耗时、导航、取消、进程退出后重试、结果展示及导出。不得根据桌面/浏览器结果标记这些设备检查通过。

## Out of Scope

- 扫描或混合 PDF 的模型识别、多页 PDF 调度、Office 嵌入图片 OCR。
- 完整 Office 页面光栅化，或替换既有原生文档解析器。
- 逐 token 或真实区域正文增量输出，以及为此扩展引擎内容流 API。
- 将服务器/桌面推理作为 App 的运行时后端。
- APK 内打包模型权重、任意自定义模型推理适配、私有仓库认证。
- x86_64/32 位 Android 构建、GPU、多线程调优、模型量化及全面内存优化。
- 并发识别、持久化多任务队列、进程终止后继续推理、开机自动恢复作业。
- 自动把旧校对迁移到重新识别生成的新区域。
- 新的 OCR 质量调参、全类型文档正确率保证、无设备测量依据的 Android 固定耗时/内存目标。
- 应用商店发布签名、上架或发布托管网站。
- 在未执行设备推理时宣称当前设备验收通过或 APK 内推理成功。

## Further Notes

用户已确认本地离线、拍照/单图、App 内模型下载、arm64-v8a、单作业及取消/重试/旧校对保留规则，并授权按现有 App 逻辑确定结果展示方式。用户已接受当前环境先交付 APK、本机真实推理和界面预览，并保留设备验收事项。此次任务是形成和发布规格，不表示推理代码已经实现。

领域语言沿用“真实识别”“结果回放”“页面”“展示区域”“DocumentIR”“阅读顺序”“校对内容”“待识别输入”，并尊重 docprase 对 LayoutBlock、Region、内容归属和识别前页面计划的区分。本地离线架构决定沿用既有 ADR。

历史模型清单中的全部九个必需工件与生产配置的文件名和固定 SHA 相符，但实施时仍需核验实际仓库版本和下载文件。所需模型磁盘空间约 576 MiB；历史 Linux 单图峰值内存约 2.3 GiB，且存在 partial 结果。这些记录不是当前构建的验证结果或 Android 性能承诺。

当前环境具备 Android SDK、NDK、CMake 和 JDK，但没有连接 Android 设备或现成模拟器。实施交付必须包含可查看的界面预览、普通/测试 APK、一次新的桌面真实识别证据、受影响测试结果及未完成的设备验收清单。

主要验收结果为：新输入能够经真实引擎产生 DocumentIR 和资源；App 能正确适配、展示、保存和导出这些内容；APK 包含实际生产 native 后端；未验证的设备运行行为清楚列为待验收。设备上的最终成功标准仍为模型在 APK 内加载并对新图片完成推理，而非展示一份已有结果。

用户已确认以现有 App 原生请求／事件桥作为主要测试边界，真实引擎验证单独记录，设备验收保留待办。

## Comments

2026-10-07：用户补充要求“只在下载模型时候完成校验”，加载方式参照 MNN Chat。该要求更新上述“激活前完整 SHA 核验”的执行时机：内容 SHA 校验在下载完成边界执行并保存结果；激活前仅检查已校验下载记录、固定工件身份及文件元数据，原生加载不重复扫描模型。Ovis 初始化参考 `createLLM → set_config → load`，使用独立私有 mmap 缓存。实现及分项验证见 [加载流程交付记录](../../verification/model-loading/DELIVERY.md)。本地离线、单图、arm64-v8a 及设备待验收范围保持原约定。

用户已确认当前 Android 项目使用本地 Markdown tracker、五个默认分流标签及单一领域上下文，并选择以 AGENTS.md 作为配置入口。本规格发布到本地 tracker，状态为 ready-for-agent，不创建相邻 docprase 仓库的远端 Issue。


2026-10-07 后续要求：真实 OCR 生成时正文须实时渲染，不能以全部区域完成后的 JSON 回放代替。后续规格与实施任务见 [真实流式输出](../live-ocr/spec.md)，模型加载校验时机仍遵循此前下载时校验的约定。

2026-10-08：用户明确更新加载要求为 use_mmap=false、kvcache_mmap=false。0.7.5 同时采用原生 Release/-O3 编译，APK 保持 Debug 变体与签名；当前有效设置及验证以 [本轮交付](../../verification/native-release/DELIVERY.md) 为准，早期 mmap=true 记录保留为历史证据。
