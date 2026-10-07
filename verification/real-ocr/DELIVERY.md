# Android 单图真实 OCR · 分项交付

日期：2026-10-07（Asia/Shanghai）。范围为本地离线、arm64-v8a、拍照/单图，沿用现有 PDF/Office、JSON/ZIP、阅读、校对和导出。不修改相邻 docprase/MNN 源码。

## APK 与原生构建

- [普通版 APK](../../app/build/outputs/apk/user/debug/app-user-debug.apk)：`cn.local.ocr`。
- [测试版 APK](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)：`cn.local.ocr.test`。
- 版本 `0.7.0-ocr`，开发签名 Debug 包；构建日志：[real-ocr-build.log](../real-ocr-build.log)。
- [APK 检查](apk.json) 记录大小、SHA、AArch64、所有 DT_NEEDED 依赖闭合、公共 C ABI/JNI/LLM 符号、实际 `DOCOCR_HAS_MNN=1`/`DOCOCR_HAS_LLM=1` 编译命令及未打包权重。已修正 MNN Express 输出目录，实际包含七个必需原生库。
- 默认分库已构建；合并库绑定分支存在但未独立构建验收。打包与符号检查不证明设备模型加载成功。

## 本轮桌面真实推理

Linux x86_64；使用与 APK 同源的 docprase/MNN 生产源代码和九个固定模型工件。源文件身份见 [source-identity.json](source-identity.json)，模型大小/SHA/当前仓库 revision 见 [models-current.json](models-current.json)。docprase 工作区原有未提交改动保持原样；不能仅用 Git HEAD 代表实际编译源码。

- 新生成输入：[source.png](source.png)，1000×1380；新输出：[DocumentIR](output/document.json)、[Markdown](output/document.md)、[run-manifest](output/run-manifest.json)、[资源目录](output/assets)、[可导入 ZIP](output.zip)。
- 退出码 0，DocumentIR 1.10 状态 `ok`，10 个内容块，实际含中文、LaTeX、HTML 表格和插图；[桌面日志](desktop.log) 与 [time -v](time.log)。整次进程墙钟约 49.29 秒，峰值 RSS 2,390,284 KiB，包含模型加载，执行期间还有 Android 编译竞争；不作为设备性能承诺。
- [公共生产 C ABI 生命周期证据](lifecycle.json)：实际加载、推理中第二项返回 BUSY、运行中销毁返回 BUSY、取消到 terminal、取消无有效结果、同一引擎恢复后再次识别和纯白输入。整次约 50.45 秒，峰值 RSS 2,432,748 KiB。
- 重复作业输入：[partial-source.png](partial-source.png)（由既有 odb-09 输入规范为 PNG，本次重新执行）；[重复作业输出](repeated/document.json) 的实际状态为 `ok`。引擎自动重试可改变 partial 结果，不按预期伪造状态。
- [真实纯白输入输出](blank/document.json)：无选中区域，原生状态 `partial`、`empty_page` 证据；App 适配按该明确证据展示为空白，原始输出不被修改。
- partial 展示另用[既有真实 partial 样本](partial/document.json)，来源见 [provenance.json](partial/provenance.json) 与 [说明](partial/evidence-note.txt)。它不替代上述新输入和重复作业验证。

## 桥接模拟与浏览器展示

- [请求／事件契约测试](../recognition-ui.json)：待识别输入与历史、缺模型入口、阶段进度、导航、取消中与终态、重试、冲突、旧作业与序号隔离、完整落盘后回放、暂停展示独立于取消、加载失败和使用中模型保护。这些原生请求由浏览器模拟，不能证明 Java/JNI 在 Android 上的生命周期行为。
- [真实输出浏览器检查](browser.json)：本轮真实中文/公式/表格/插图/原图，校对、排序、原始元数据和 ZIP 再导入，以及带来源记录的历史 partial 提示；0 个页面异常。
- 可查看预览：[文字与公式](preview-text.png)、[表格与插图](preview-structured.png)、[partial](preview-partial.png)、[手机布局](preview-mobile.png)。预览由 Chromium 导入真实结果后回放，不执行模型。

## 检查结果

| 检查 | 环境与边界 | 结果 |
| --- | --- | --- |
| 普通/测试版 Java 编译与 JVM 完整套件 | Gradle；文件存储/归一化、原始输出/校对/排序/导出，模型大小/SHA，8 种非对称 EXIF 像素输入输出，既有 PDF/Office | 各 40 项，0 失败；[日志](../real-ocr-jvm-final.log) |
| 既有浏览器完整回归 | Chromium；导入/回放/历史/校对/Office 展示/导出 | 19 项通过 |
| 模型界面 | Chromium；原生宿主模拟 | 6 项通过 |
| 顺序调整 | Chromium；原始块 ID 保留，App 展示 ID 稳定，导出/再导入 | 7 项通过 |
| 长按与编辑 | Chromium；桌面和真实触屏事件、Markdown 编辑与导出 | 12 项通过；[日志](editor-regression.log) |
| 新识别桥接场景 | Chromium；模拟 native 请求和事件 | 6 组通过；[日志](../recognition-green.log) |
| 真实输出展示 | Chromium；实际模型输出文件 | 3 组通过；[日志](browser.log) |
| 原生 ABI 既有回归 | Linux；contract、printed_page_cancel、job_control、cli_job_control（受控测试后端） | 4 项通过；[日志](abi-regression.log) |
| 实际 APK 内容 | NDK ELF/编译命令与 ZIP 静态检查 | 通过；[日志](apk.log) |

代码审查记录将在实施提交后补充。工具与构建入口见 [开发说明](../../docs/REAL_OCR.md)。

## 设备待验收

当前 `adb devices` 无设备；以下项目尚未执行，不能标为通过：

- [ ] 在 arm64 Android 安装普通/测试 APK，实际离线下载/校验/加载九个模型，确认 JNI 和全部运行库加载。
- [ ] 新拍摄及导入图片首图与重复推理，对比原图、实际 DocumentIR 和资源；断网后完成推理。
- [ ] 使用非对称 EXIF 1～8 文件，核对 Android 解码、PNG 规范、相机方向与结果几何。
- [ ] 实测加载/推理内存、耗时、热状态与系统后台限制；当前 Linux 数字不代表手机可用内存。
- [ ] 导航继续、文档重开、第二项冲突、安全取消、清理期间重试限制、使用中模型删除/替换保护。
- [ ] 系统杀进程后输入保留且可重试，不承诺后台推理续跑。
- [ ] 成功/partial/空白/失败的结果显示、校对、排序、TXT/MD/ZIP 分享和再导入；重新识别不覆盖旧校对。
