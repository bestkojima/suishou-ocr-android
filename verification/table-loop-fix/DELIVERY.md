# 发票表格终态与循环输出保护

日期：2026-10-13。范围：Android App、模型适配及 docprase 的 repetition 判定。未提交、推送或发布版本。

## 表格问题

用户补充「识别途中正常渲染，完成后显示错误」。构造与引擎真实终态契约一致的发票表格：HTML 有合并单元格和错误 LaTeX，status=partial，content.text 为空，provenance.raw_output 完整。

修改前 `node web/verify-invoice-table.mjs` 在「完成后仍须显示原始发票表格」失败（0 个 table）；Android `rejectedInvoiceTableRemainsReadableAndEditable` 同样失败。

根因是 docprase 对未通过结构/公式校验的表格清空正文，生成中使用 raw 的展示在完成后切换到空正文。Android DocumentStore 与浏览器规范化现在仅在非 ok 表格正文为空时恢复原始输出，用于显示、校对和应用 Markdown 导出；保留待核对状态，不改原始 DocumentIR 或覆盖已有有效正文。

回归覆盖合并行/列、异常公式、校对保存重开、375px 竖屏/横屏，以及模拟 Android 桥从区域完成到整页保存的切换。截图见 `invoice-portrait.png`，结果见 `ui.txt`。

## 循环保护

- AndroidGenerationStream 使用 4096 字节尾窗，周期上限 256 字节，至少连续 8 次且累计至少 96 字节才拦截相同片段；检查间隔至少 16 新字节，等待完整 UTF-8 尾字符。
- 连续 12 个只有编号、没有正文的完整行触发空编号保护；正常带题目的编号不触发。
- 表格不按重复 HTML/Markdown/公式结构片段截停，允许正常重复行；检测单元格内持续重复。空白与常见表格分隔线不作为循环证据。
- MNN 首次 response 生成一个 token，随后沿同一会话逐 token generate；每步检查取消与循环。检测命中后不再 decode，保留已经输出的原文。`stop_reason=repetition`，不会扩大 token 预算重试，后续区域正常重置执行。
- 取消优先，原有 token/时间上限、采样、prompt、视觉预算、mmap 设置不变。未修改固定 MNN。
- 源仓库 `/home/dr/project/docprase/src/output_assessment.cpp` 与 App 同名快照同步一个判定：明确 repetition 直接归为 anomalous，覆盖纯标点、表格及长正文尾部，避免误报 token 用尽。没有同步源仓库其他未提交修改。

修改前 `python3 tools/verify_generation_loop.py` 对实际 Android 流缓冲的「循环输出应在生成中截停」断言失败；增加停止后，额外的纯标点终态回归先失败，再修正停止原因判定。最终见 `generation-guard.txt`。

## 真实模型验证

使用同源 Android 适配的 Linux 共享库，固定真实 Ovis 模型、4 线程和此前 6800×9067 用户扫描页。最终结果在 `real-ovis-final/`，逐区对比断言在 `real-verification.json`。

| 原异常 | 本次行为 |
| --- | --- |
| 日期循环 48 次 | 8 次后 repetition 停止 |
| 递增空编号至 107 | 第 12 项后 repetition 停止 |
| 句点循环，原先重试后 2040 个 | 34 个独立句点后停止，无扩大预算重试 |

三处原始输出均为旧输出的前缀，终态均为 anomalous；另外 14 区原始输出与此前逐字一致，且异常之后的区域仍正常完成。全部 17 区各一次尝试。真实库及实际加载的 MNN SHA 记录在 benchmark.json；这不是 Android 真机推理或受控性能基准。

另逐字节回放历史异常输出，验证 UTF-8 分片和循环检测；历史 Ovis 14 区、GLM 17 区正常输出均未误停，合成重复表格行与带正文题号未误停。GLM 此轮仅做历史输出回放，没有重新执行真实 GLM 模型。

## 本地检查与产物

- `python3 tools/verify_generation_loop.py`：循环、反例、UTF-8、后续区域隔离和停止原因通过。
- `python3 tools/verify_recognition_stream.py --library /tmp/ocr-table-loop-host/docprase/libdococr_c.so --output verification/table-loop-fix/stream`：实际共享库流缓冲、重试、异常结束、隔离与清理通过。
- `node web/verify.mjs`、`node web/verify-invoice-table.mjs`、`node web/verify-live-recognition.mjs`、`node web/verify-recognition-navigation.mjs` 通过。新发票回归已纳入 `npm run test:web`。
- docprase `dococr_block_result_test` 通过。
- `./gradlew assembleUserDebug assembleLabDebug testUserDebugUnitTest testLabDebugUnitTest lintUserDebug lintLabDebug` 通过：两版分别 75 项 JVM 测试，原生 Release 编译和 lint 通过。
- 本地 APK 保持 `0.7.8-ocr`／15、原应用标识与 Debug 签名；路径和 SHA 见 `build.json`。未创建新 release。

## 边界

发票回归使用终态契约构造样本，尚未取得用户此次发票原图；Titan_1 真机仍待验收。检测是启发式保护，不能消除模型退化根因、识别所有异常续写，也不能代替准确率验收。重复表格行因可能合法而保守放行，未命中的退化仍受原 token/时间上限约束。截停前已经输出的异常片段保留供核对，没有自动改写为正确内容。
