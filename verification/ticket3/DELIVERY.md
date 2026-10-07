# ticket3 结构化结果、校对与导出交付

日期：2026-10-07（Asia/Shanghai）。任务：[03：贯通结构化结果、校对、排序和导出](../../.scratch/android-real-ocr/issues/03-structured-results-proofreading-export.md)。本轮基线：`295edc31b57629c972250c27914538df0927016b`；代码提交：`6976bc9`、`9d973a7`，位于当前 `main` 分支。

## 实施结果

既有代码已包含真实 DocumentIR 展示、校对、同页排序、历史和另存重新识别。本轮核验并修复以下边界：

- Android 与浏览器多次 ZIP 导入／导出保留首次 `original-document.json`，当前阅读顺序和校对继续独立保存。
- 浏览器完整保留原生二进制资源、图片、原图和 `run-manifest.json`，不再只保存可显示的图片；导出 App 状态不重复嵌入完整资源数据。
- 页面单独标记 partial 时仍提示部分成功，并保留可用正文；JSON/ZIP 导入复用同一判定，实际 `empty_page` 结果明确显示为空白页。
- 已声明的合法本地图片路径可位于 `assets/` 外，刷新及历史重开后仍能显示。
- 浏览器导出把页面原图关联转换为有效本地路径，ZIP 目录项不再作为零字节资源文件导出。
- 补充检查另存重新识别后的区域、校对、阅读位置和资源目录隔离，旧结果保持完整。

首次原始输出丢失、非图片资源遗漏、页面 partial 误判、partial 提示遗漏、合法图片路径被拒绝、空白显示和目录项误导出均有修复前失败证据。对应日志以 `*-red.log` 命名，最终通过结果以完整回归为准。

## 验证结果

| 检查 | 结果与证据 |
| --- | --- |
| 普通／测试版 JVM 完整套件及 APK 构建 | 各 44 项，0 失败／错误／跳过；[Gradle 日志](gradle-final.log)、[XML](jvm) |
| 既有浏览器回归 | 19 项；[日志](web.log)、[报告](web.json) |
| 模型界面 | 6 项；[日志](models.log) |
| 同页排序 | 7 项；[日志](reorder.log) |
| 长按拖动与 Markdown 校对 | 12 项；[日志](editor.log) |
| 原生请求／事件桥场景 | 10 组；[日志](recognition.log)，原生宿主为浏览器模拟 |
| 实际结构化输出与往返保存 | 8 组；[日志](real-results.log)、[报告](browser.json) |
| APK 架构、依赖、符号、生产后端和无权重打包 | 通过；[日志](apk.log)、[大小与 SHA](apk.json) |
| 规范／规格双路代码审查及补充复审 | 各 0 项剩余发现；[审查报告](CODE_REVIEW.md) |

汇总与内容身份：[results.json](results.json)。项目为 Java／JSX，没有独立 TypeScript 类型检查；本轮通过普通／测试版 Java 编译和 esbuild 打包检查。全部浏览器检查共 62 项。

APK：[普通版](../../app/build/outputs/apk/user/debug/app-user-debug.apk)、[测试版](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)，均为 arm64-v8a Debug 包。

可查看预览：[文字与公式](preview-text.png)、[表格与插图](preview-structured.png)、[partial 提示](preview-partial.png)、[手机布局](preview-mobile.png)。

## 内容来源与验证边界

本次复用既有桌面生产引擎的 [结构化输出](../real-ocr/output/document.json)、[重复作业输出](../real-ocr/repeated/document.json)、[空白输出](../real-ocr/blank/document.json)，以及带 [provenance](../real-ocr/partial/provenance.json) 的历史真实 partial 输出。JSON 的 SHA 和 schema 版本见汇总；首次桌面推理和实际模型／源码身份见 [原交付记录](../real-ocr/DELIVERY.md)。本次没有重新运行模型。

页面独立 partial 是兼容性用例；`images/` 路径和 ZIP 目录项使用真实内容构造路径变体；页面原图往返使用按原生应用状态契约构造的 App 状态，并非设备导出产物。JVM 文件存储检查和浏览器 AndroidHost 模拟不证明 Android 解码、JNI 执行或原生资源清理时序。

## 设备待验收

`adb devices` 仍无连接设备。APK 内实际模型加载、新图片推理、资源显示、校对排序、系统分享和 ZIP 再导入仍待设备验收，沿用 [既有设备清单](../real-ocr/DELIVERY.md#设备待验收)。取消后重新识别组合场景按 ticket3 原范围留给最终交付验证。本次构建与模拟通过不表示设备推理通过。
