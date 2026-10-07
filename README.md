# 随手识别 · Android 文档解析与 JSON 验证版

完整的架构、接口、数据结构、构建测试与 OCR 接入说明见 [开发文档](docs/DEVELOPMENT.md)。

第一版采用已确认的相机首页和流式文档界面。Android 原生层负责相机、文件和下载；离线 WebView 内的 React + Streamdown 负责 Markdown、表格、公式、图片。现已通过 JNI 接入 docprase 的 PP-DocLayoutV3 + OvisOCR2 生产后端，首轮为 arm64-v8a 本地离线单图识别。Android 内实际模型加载和推理仍待设备验收；本轮构建、桌面真实输出及预览见 [交付记录](verification/real-ocr/DELIVERY.md)。

## 安装

- 普通版：`app/build/outputs/apk/user/debug/app-user-debug.apk`，应用 ID `cn.local.ocr`。
- 测试版：`app/build/outputs/apk/lab/debug/app-lab-debug.apk`，应用 ID `cn.local.ocr.test`，可与普通版同时安装。
- 两者当前均为开发签名的调试 APK，不是商店发布包。普通版不包含测试开关、不启用 WebView 调试；测试版可在设置中切换测试模式并调节回放速度。
- Android 8.0 / API 26 起；需要支持现代 JavaScript 的 Android System WebView（Chromium 100+）。

首页选择“文字 / 表格 / 图片”或“公式 / 图片”即可离线回放真实识别结果，无需下载模型。样本来自 `/home/dr/project/docprase`，复制文件与来源记录保存在 `prototypes/ocr-streaming/fixtures/`。原项目未被修改。

## 已实现的流程

| 输入或操作 | 当前行为 |
| --- | --- |
| 拍照、图片导入 | Camera2 预览与拍摄，系统文件选择，保存原图并进入结果页；标明等待 OCR，不伪造文字 |
| DocumentIR JSON | 按页、reading_order 和稳定区域 ID 回放现有结果；支持已有 partial 状态提示 |
| 带图片的 JSON | 将 document.json 与 assets 一起打包 ZIP 导入；缺失图片显示固定占位及重试按钮 |
| PDF | 参考 RapidDoc auto，最多抽样 10 页判断整份文档；文本路径提取文字与图片，OCR 路径逐页转图并标记待 OCR |
| Word | DOCX 提取文字、表格和嵌入图片；DOC 提取段落和图片，不保证结构化表格；不声称复原原始版式 |
| Excel | XLS、XLSX 按工作表提取单元格，保留合并区域、常见数字格式、公式缓存值；XLSX 图片按关联归入工作表；不执行公式或宏 |
| PPT | PPTX 按 presentation.xml 顺序逐页提取文本、项目符号、表格和图片；旧 PPT 逐页提取文本，图片暂列为附件 |
| 流式显示 | 暂停、继续、重放，原图查看与缩放，竖屏文档优先、宽屏双栏 |
| 历史与校对 | 本机保存完成区域进度、阅读锚点和逐区域修改；重启后继续；删除会清理文档资源 |
| 导出分享 | TXT、Markdown、含图片 ZIP，Android 系统保存和分享；ZIP 保留原始 JSON 及校对快照，重新导入可恢复修改 |
| 模型下载 | 两个默认仓库及自定义公开 ModelScope 仓库；链接添加、文件筛选与选择下载、连接/下载/校验状态、断点续传、网络限制、空间检查、SHA-256 校验（无哈希文件明确标为仅大小校验）、删除 |

PDF 决策阈值与顺序参考 [RapidDoc pdf_classify.py 固定版本](https://github.com/RapidAI/RapidDoc/blob/60cd038d424e0e839462ba4bd96345e0279290fe/rapid_doc/utils/pdf_classify.py)：平均字符数、页面比例、Unicode 映射、字体实际使用、异常字符和乱码规则。高图片覆盖率不单独触发 OCR。Android 用 PDFBox 收集指标，Unicode 映射与 PDFium 存在差异，不能保证所有真实 PDF 分类相同。分类是文档级，混合文档文本路径中无文字的页面保留图片并标为待版面分析；尚未接入 RapidDoc 所需的模型区域分析与区域 OCR。页面以 200 DPI 渲染，Android 限制单页 400 万像素。测试工具 tools/verify_rapiddoc_policy.py 从固定版本的原始函数生成指标对照样本，不代表真实 PDF 端到端验证。原文页图可供核对；复杂表格和阅读顺序不保证准确。Office 内容解析不保证分页、浮动对象、图表、SmartArt、复杂公式和全部数字样式的还原；**尚无完整 Office 页面光栅化引擎**，图片型内容只提取已有嵌入图片。完整 Office 版式转图是与真实 OCR 分开的一项待完善能力。

## 流式稳定策略

完成的区域保持 DOM 身份；只更新当前区域。公式及表格完整接收后再渲染，未闭合的行内公式暂存。图片按资源宽高预留空间，加载、失败或重试不改变占位高度。用户向上翻阅后停止自动追随，旋转时按区域 ID 和相对位置恢复。字体随 APK 打包，渲染无需访问 CDN。

当前段落自然换行、首次展开表格或公式仍会改变当前区域的高度；并非保证任何输入下所有像素都不移动。浏览器测试覆盖已完成区域身份、向上阅读时的滚动稳定和图片重试占位。

## 模型源

- https://modelscope.cn/models/dr3334/PP-DocLayoutV3-mnn
- https://modelscope.cn/models/dr3334/ovrics-ocrv2_mnn

清单来自 ModelScope repo/files API。逐文件固定 revision，并记录远端提供的 SHA-256；内容存储在应用私有 models 目录，partial 子目录保留可续传进度。仅对带有正确 Content-Range 的 206 响应追加；续传请求返回 200 时重新发起不带 Range 的完整请求。提供 SHA 的文件需通过大小与 SHA 校验；没有 SHA 的文件仅检查大小并明确标记，不称为哈希校验通过。

早期清单快照记录过 11 个文件约 582 MiB，仓库后续可变化。网络验证仅下载小文件或有限字节范围，**当时没有下载完整权重，也没有验证 MNN 推理加载**。早期清单见 `verification/modelscope-live.json`，后续小文件与范围续传验证见 `verification/modelscope-network.json`。下载进程被系统终止后需要在页面点击继续；本版没有自动开机重启下载。

## 开发与验证

本机环境：JDK 17、Android SDK 35、Build Tools 35、Gradle 8.11.1、Android Gradle Plugin 8.9.3。环境本身可以编译 APK。Web 依赖通过 lockfile 固定，KaTeX 为 0.16.47，math 插件为 1.0.3。

```sh
npm ci
# SDK 路径配置在未提交的 local.properties，或设置 ANDROID_HOME。
./gradlew assembleUserDebug assembleLabDebug
./gradlew testUserDebugUnitTest lintUserDebug
npm run build:web
./gradlew testUserDebugUnitTest # 生成 Office 解析回放测试包
npm run test:web
npm run preview
```

Gradle 的 preBuild 会自动构建离线网页和复制样本。浏览器预览默认 `http://localhost:4174`。测试脚本可通过 `CHROMIUM_PATH` 指定 Chromium；本机默认路径已配置。

浏览器可验证 JSON / ZIP / 图片输入、回放、历史、校对和导出。拍照、PDF / Office 导入、系统分享及后台模型下载依赖 APK 原生服务，浏览器不会伪装这些功能已执行。

- 浏览器测试：`web/verify.mjs`，结果及截图在 `web/test-results/`。
- JVM 测试：`app/src/test/java/cn/local/ocr/`，覆盖 Office 内容提取、资源路径和 ZIP、文档保存/导出再导入、HTTP 续传协议与中断。
- 模型在线小文件验证：`python3 tools/verify_modelscope.py`。
- Android Lint 报告：`app/build/reports/lint-results-userDebug.html`。

当前没有连接 Android 设备，也未运行设备模拟器。相机方向/权限、真实系统文件选择、WebView 设备兼容性、PDF 渲染及后台服务生命周期仍需真机验收。JVM 测试不能代替这些验收。

## 单图真实 OCR（0.7.0）

拍照/导入先保存原始文件与 EXIF 方向规范后的 `source.png`。缺模型时保留待识别输入；设置 → 识别模型下载两个默认仓库的九个必需工件后，可“校验并加载识别模型”，再回到输入点击“开始识别”。工件齐备时采集后自动开始校验和识别，APK 不包含权重。

`RecognitionController` 在应用级工作线程运行 JNI/C ABI，同一时间只允许一个作业；返回首页继续运行，取消等待原生执行和资源清理完成。失败可重试，进程退出后的输入可重新打开；成功后的“重新识别（另存）”保留旧结果和校对。模型使用中阻止删除和替换。完整 JSON/Markdown/资源落盘后复用既有结果回放、校对、排序及导出；暂停输出仅暂停回放。

Android CMake 从相邻 `../docprase` 和 `../MNN` 的现有源代码构建；不修改两个源引擎仓库。默认 MNN 分库，也提供合并库的 target 绑定分支。首轮使用 CPU 单线程；不新增 PDF/Office OCR、服务器后端或 token 增量协议。两种 Debug APK 与 [真实内容预览](verification/real-ocr/preview-structured.png) 见交付记录。

## v0.2 Office 分流

参照 RapidDoc 的文档类型分流方式独立实现 Android 解析，没有嵌入或复制 RapidDoc 的 Python 实现。依据：

- [Office 分流](https://github.com/RapidAI/RapidDoc/blob/main/rapid_doc/backend/office/office_analyze.py)：按 DOCX / PPTX / XLSX 选择原生解析器。
- [旧格式转换](https://github.com/RapidAI/RapidDoc/blob/main/rapid_doc/utils/office_converter.py)：上游通过 LibreOffice 将旧 DOC / PPT / XLS 转为新格式。Android 本版没有该桌面依赖，旧格式采用 Apache POI 提取，因此能力不等同于上游。

工作表/幻灯片信息保存在 pages，区域保留 page 来源。图片保存为资源，并生成 ocrCandidates 待处理记录；它们不是已经完成识别的结果。所有输出进入原有文档区域流和 Markdown 渲染。原生解析内容按区域直接显示，避免大表格逐字等待；JSON 回放保留可调速的字符切片模拟。PPTX 按 XML 内容顺序提取页内对象，尚未实现 RapidDoc 的完整空间阅读顺序算法；不读取讲者备注和母版内容。

XLSX 公式优先使用缓存结果，没有缓存则显示公式及明确提示，不重新计算。旧 PPT 暂不保留表格结构、图片所在幻灯片或复杂绘图。新版本安装包为 artifacts/suishou-ocr-{user,test}-0.2.0.apk。自制 Excel / PPTX 示例在 artifacts/office-samples；相应解析 JSON ZIP 在 verification/office-fixtures，可在浏览器中回放。测试源码同时覆盖这些真实文件包。

## 模型下载修复（0.4.0）

设置 → 识别模型：可添加公开 ModelScope 仓库 ID 或网页链接，浏览文件并下载所选文件。默认选择带校验值的非说明文件，可自行勾选 README 等其他文件。浏览器支持添加/移除仓库入口，下载与在线文件清单使用 Android 服务。私有仓库登录与任意模型的推理适配不在本次范围内。

进度在每次写入后发布，界面每秒读取最新内存进度；下载状态查询、暂停和仓库清单请求独立于文档处理队列。连接、下载、SHA 校验分别显示，连接长时间无字节更新时提示等待服务器响应。网络中断保留部分文件，手动继续。任务结束或进程被系统终止后可重新选择文件继续。

文件保存在应用私有 models/作者/仓库/files/原始路径 下，不再按内容哈希分散模型目录；同内容不同路径文件分别保存。旧版本完整文件校验后复用，旧 .part 断点迁移后继续。默认“仅 Wi-Fi”沿用 Android 非计费网络判定。

验证：`node web/verify-models.mjs` 覆盖仓库管理、文件选择和模拟原生事件进度；`python3 tools/probe_modelscope.py` 验证两个默认仓库的小文件 SHA 和有限范围续传，不下载完整模型。Android 服务生命周期与实际网络仍需真机验证。

## 区域顺序调整（0.5.0）

结果页底部点击“调整顺序”，使用上移/下移按钮修正同页区域顺序，点击“保存顺序”应用。打开面板会暂停回放；仅可移动已完成的区域，未完成内容继续输出时不会被跳过或重复。关闭面板不保存修改。

历史记录、校对内容与图片保持关联；Markdown/TXT 导出按新顺序排列。Android 导出的原始 DocumentIR 保留区域几何等原数据，只更新 reading_order。ZIP 的应用快照也保存新顺序，重新导入后可继续调整。验收：`node web/verify-reorder.mjs`。

## 长按拖动与简易 Markdown 编辑（0.6.0）

正文中的已完成区域可长按约 450 毫秒选中，拖到绿色插入线处松手，自动保存新顺序；靠近阅读区上下边缘时自动滚动。普通滑动阅读不会触发排序，松手在阅读区外或按 Escape 可取消。仍保留“调整顺序”面板与上移/下移按钮。拖动会暂停输出，只允许同页已完成区域重排。

“校对”框增加标题、加粗、斜体、列表、编号、引用、公式、换行和插入表格按钮，以及撤销/重做、效果预览。格式作用于选区或当前行；点击“保存修改”后生效。已有 HTML 表格可直接改单元格文字，不是可视化表格编辑器。

`node web/verify-editor-drag.mjs` 包括 Chromium 触屏事件、普通滑动、拖动保存及编辑导出验收。Android WebView 真机长按手势仍需实机验证。
