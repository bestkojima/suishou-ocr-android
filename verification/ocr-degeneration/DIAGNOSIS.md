# BUG-002：低像素与大字少字输入的重复生成诊断

日期：2026-10-09。范围：按用户要求先复现和验证，**本阶段不实施修复，不改变生产参数，不发布 APK**。根因尚未确定。

## 已复现的事实

使用项目固定真实模型、Linux x86_64 同源 Android 原生适配、Release、4 CPU 线程、`use_mmap=false`／`kvcache_mmap=false`。0.7.6 未改 0.7.5 的识别原生源码，因此复用已有同源库；工件按既有下载登记及大小核对，加载不重新扫描全部权重 SHA。当前没有 ADB 设备，也没有收到用户的问题原图或设备配置；这些是同类症状的真实模型复现，不能代替 Titan_1 原图验收。

| 输入 | 原始流表现 | 重复运行 |
| --- | --- | --- |
| 1000×1000，100 万像素，仅一个“识”字，字号 760 | `## 口` 后持续重复 `## 1` | 3/3 复现 |
| 既有正文/公式/表格样图缩至 128×177，共 22,656 像素 | reqr0005 持续重复 `## 目` | 3/3 复现 |
| 同一正文样图缩至 256×353 | 进入实际生成，正常停止，没有达到持续循环判据 | 1 次对照 |
| 同一 100 万像素画布、同一个“识”字，字号 64 或 320 | 正常生成 `## 识` | 各 1 次对照 |
| 100 万像素、2 字“测试”或 4 字“随手识别” | 正常结束，未出现持续循环 | 各 1 次对照 |

不是所有低像素或大字少字输入都会循环。把触发的大字整图缩到 512、256、128、64 像素宽，本组测试反而未出现循环；32 像素宽及部分低像素正文图没有检测出区域、未进入语言模型，**不能算识别通过或“无循环”证据**。

证据：[results.json](results.json)、[大字整页三次复现](repeatability/report.json)、[低像素整页三次复现](low-repeatability/report.json)、[初始对照](screening/report.json)、[字号对照](probe-baseline/report.json)。

## 复现闭环与最小化

判据只用于这些不含过量重复的固定样图：原始生成流中的同一连续片段重复至少 6 次，累计至少 32 个非空白字符。这是“持续循环”诊断判据，不能直接移为生产截断规则；少量重复、识别错误和完整性问题另看原始输出。

- 大字：移除外围空白仍复现；提取实际识别区域后只剩 692×696 的一个字。
- 低像素：循环实际来自 20×8 的原始区域，对应原清晰图中的“商品统计”。直接把这个小图重新走整页版面检测，没有进入识别；改为在生产区域 backend 接口处复跑，移除界面、版面检测、其他区域与历史，每次仍重复 `## 目`。
- 两个最小样例各 3/3 复现。整页与直接区域接口送入视觉模型的 RGB 像素完全一致，参见 [minimization-equivalence.json](minimization-equivalence.json)。低像素视觉画布为 256×256，大字为 544×576；画布像素数不等于原始文字信息量。
- 整页基线保持产品的 512 token 上限。为缩短最小反馈循环，仅在诊断命令中降为 64，仍连续生成 16 个重复周期；产品上限保持不变。未声称找到了普适的最小像素或字号阈值。

已实际运行的快速命令（已有临时 probe 库时）：

```bash
python3 tools/debug/direct_region.py \
  --library /tmp/ocr-degeneracy-native/libdococr_c_diagnostic.so \
  --input verification/ocr-degeneration/captured/low-region.png \
  --output verification/ocr-degeneration/direct-low-baseline
```

输出节选，无凭据：

```text
REPETITION low-region.png 1 2.662 '## 目\n\n## 目\n\n...'
REPETITION low-region.png 2 1.543 '## 目\n\n## 目\n\n...'
REPETITION low-region.png 3 1.589 '## 目\n\n## 目\n\n...'
exit=1
```

退出 1 表示捕获 bug；0 表示进入生成且未达到循环判据；2 表示运行错误或未覆盖。源码库默认 greedy，这些对照也均采用确定性最终选词，三次判定及 token 周期一致。相同输出目录可复跑，不会混入前一次 native trace。

## 可证伪假设与证据

排序已在实验前向用户说明。以下仍区分触发解释和重复延续机制。

| 排序 | 假设与预测 | 当前证据及判断 |
| --- | --- | --- |
| 1 | 如果字形尺度或细节经视觉处理后影响模型解释，那么保持文字/画布不变，只改变字号或源分辨率应改变复现情况 | 同一“识”字、100 万像素画布，64/320 字号正常，760 循环；降采样大字整图后也能停止循环。支持输入表示与症状有关，**尚未确定视觉编码、缩放、模型适用范围或数值运行中的具体机制** |
| 2 | 如果当前解码方式允许重复延续，同一输入启用有效 penalty 后应该改变重复情况 | 单独提高 greedy 的 penalty 无效；启用 penalty 采样后部分样例缩短，但仍错识，大字对轻度 repetition penalty 仍循环。证明该配置能影响延续，不证明它是初始误认的根因 |
| 3 | 如果流式适配重复追加，而模型没有反复生成，则真实 output_tokens 应没有对应周期 | 低像素 `[550,220,96060,271]`、大字 `[550,220,96116,271]` 均重复 16 次，模型 generate_str 也与循环原始流一致。**这两个样例的实际 token 已在重复**，纯追加/重绘不能解释它们 |

大字的实际视觉图包含完整字形；低像素实际视觉图明显模糊。它们是观测，不足以单独确定原因。[大字视觉输入](probe-baseline/one-tight-run-1/native-trace.jsonl.reqr0001.visual.png)、[低像素视觉输入](probe-baseline/page-low-128-run-1/native-trace.jsonl.reqr0005.visual.png)；原始 token 和实际加载配置在对应 `native-trace*.jsonl` 中。

## 采样与 penalty 对照

每项采用同一原始区域、同一视觉像素、同一 prompt、4 线程、64 token；每种配置/每类输入运行三次，共 36 次。`penalty_sampler=greedy` 明确保持确定性。实际 `dump_config` 验证参数已生效，未只依据请求值或原 manifest 的固定字段。

| 配置 | 低像素持续循环 | 大字持续循环 | 代表结果 |
| --- | --- | --- | --- |
| greedy，repetition=1.0 | 3/3 | 3/3 | 重复“目”／“口”，token_limit |
| greedy，仅 repetition=1.1 | 3/3 | 3/3 | 与基线相同，数值没有进入惩罚路径 |
| penalty，repetition=1.0 | 3/3 | 3/3 | 中性惩罚仍与基线相同 |
| penalty，repetition=1.05 | 0/3 | 3/3 | 低像素结束为“目见设计”，仍错识 |
| penalty，repetition=1.1 | 0/3 | 3/3 | 同上，大字仍循环 |
| penalty，repetition=1.0，frequency=0.2 | 0/3 | 0/3 | 低像素仍“目见设计”；大字为两次“口”，仍有短重复和错识 |

“0/3”只表示未达到上述持续循环判据，不等于没有重复或正确识别。这些输出均未恢复两个样图的已知文字，不能作为已解决证据，更不能据此给出全局 OCR 准确率。详见 [参数、完整输出、实际命令与结果](sampler-matrix/summary.json)。

MNN 支持 `penalty` 和 `mixed`，可调整 repetition/presence/frequency 等惩罚，但当前固定源码的纯 greedy 分支直接 argmax，跳过 penalty；temperature/top-p 等在这个分支也不生效。此项是代码路径事实，不是循环触发根因。[MNN 官方采样说明](https://mnn-docs.readthedocs.io/en/3.6.0/transformers/llm.html)；以本项目固定 `native/MNN/transformers/llm/engine/src/sampler.cpp` 为准（`buildPipeline`、`sample`、`stepPenalty`）。

App 生成有效配置时固定 greedy，单独编辑下载的模型 config 不会替代该覆盖。未来若允许配置，需同时处理加载前有效参数、采样路径和实际配置记录。本阶段只在临时库中注入参数。随机 temperature/top-p 未测试；以后须固定真正生效的随机种子或报告重复运行分布，不能用一次输出作结论。

## 字符串截断及其他办法的评估

以下均是后续候选，尚未实施或验收：

1. 按实际区域检查字形尺度与细节，再做视觉预算、缩放/补白的单变量对照。整页 MP 不能替代区域信息量；单纯放大模糊小图不会恢复细节，统一降低分辨率也没有验证为通用办法。
2. 在真实生成过程中监测持续 token/字符周期，接入解码停止、保留原始证据并标记异常。前端剪字符串只改变显示，不能保证停止推理。正常重复字、表格行和公式必须另设负例验证。
3. penalty 可影响延续，但本次参数仍造成错识，不选定生产默认值。进一步评估需同时覆盖正常正文、表格、公式和可合法重复的内容。

当前既有 `output_assessment.cpp` 的重复检测发生在生成完成后，表格走结构校验而不使用该重复检测。诊断中通过已有 job_cancel 请求取消后，模型仍生成至 token_limit，job 随后标记 cancelled_after_backend_call；因此若做实时截断，需要验证真正停止 decode，而不只结束界面或外层作业。

## 边界与清理

- 根因未确定，用户问题原图与 Titan_1 仍未对照。两类合成/受控样例的复现与参数影响已确认，其他输入不可外推。
- 根据用户“这一步并不要求解决”，不执行修复和“原始复现转绿”的验收。闭环现在仍应返回红，BUG-002 未关闭。
- 所有 probe 工具集中在 `tools/debug/`，实验生成源码/库位于 `/tmp/ocr-degeneracy-native/`，捕获文件位于本诊断目录；`[DEBUG-b002]` 只在诊断生成源码和 trace，生产源码没有临时日志。
- [unchanged-release.json](unchanged-release.json) 核对原始 host 生成源码和 v0.7.6 两版发布 APK 未改变；应用版本、生产采样、视觉预算及 mmap 默认值不变。本任务未发布 GitHub 版本。

复跑准备与参数矩阵：

```bash
python3 tools/debug/make_degeneracy_fixtures.py
python3 tools/debug/build_degeneracy_probe.py \
  --baseline /tmp/suishou-ocr-075/host-release-no-mmap \
  --output /tmp/ocr-degeneracy-native
python3 tools/debug/sampler_matrix.py \
  --library /tmp/ocr-degeneracy-native/libdococr_c_diagnostic.so
```

需要已有 Linux 同源 host 构建、项目固定模型和 Pillow/Noto 字体；原始整页复现使用 `tools/debug/ocr_degeneracy.py`。构建记录见 [probe-build-direct.json](probe-build-direct.json)。
