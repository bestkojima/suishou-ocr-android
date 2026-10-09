# GLM-OCR 配置适配与真实识别对照

2026-10-09。用户要求“使用 glm ocr 试试”，并明确由模型文件夹的 config 字段适配。已实施、完成本地验证；尚未发布新版，BUG-002 保持未关闭。

## 实现

- 应用新增 GLM-OCR 选择入口及配套文件清单，Ovis 仍为默认。切换先卸载空闲模型，加载、识别和下载期间拒绝切换；连续区域仍共享模型、重置会话。
- App 自有原生后端读取模型目录运行配置和 `llm_config.json`，按字段绑定词表、独立 embedding、语言/视觉工件、任务提示词、模板与图像 token。没有依据目录名的模型分支。
- 原始下载配置保留校验契约；同目录生成可编辑的 `ocr_runtime.json`，源配置已有 `ocr` 时保留其值。GLM 预设 text/formula/table 分别使用 `Text Recognition:`、`Formula Recognition:`、`Table Recognition:`。
- 沿用固定 MNN 提交 `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`／3.6.1，未修改 MNN 或 vendor docprase。CPU 线程、Release 编译、关闭 mmap/KV mmap、私有临时目录和下载完成校验约定不变。
- 模型实际配置在 load 后检查，manifest 记录模型名称/类型、image_pad、提示词 SHA 和采样等。旧 backend 标识仍为 ABI 兼容别名，实际 GLM 为 `model_name=GLM-OCR`、`model_type=glm_ocr`、`image_pad=59280`。

配置字段及优先级见 [MODEL_CONFIG.md](../../docs/MODEL_CONFIG.md)。修改运行配置后须重启应用，或切换到另一模型再切回来并加载；已缓存引擎不在区域间重新读取配置。

## 模型来源与加载

工件来自 MNN 团队的 [taobao-mnn/GLM-OCR-MNN](https://huggingface.co/taobao-mnn/GLM-OCR-MNN)，固定提交 `d77c8eb81b0c55418f56959dfccd4c61c48a2d6a`，通过 hf-mirror 下载。核对 [ModelScope MNN/GLM-OCR-MNN](https://modelscope.cn/models/MNN/GLM-OCR-MNN) 的大小/SHA，一致。下载后完整校验一次，见 [download.json](download.json)；模型在 `/home/dr/project/models/GLM-OCR-MNN`，不加入 Git 或 APK。

原官方运行配置含 `sampler_type=penalty` 与旧 `penalty=1.1` 字段；固定 MNN 的重复惩罚读取 `repetition_penalty`。本轮采用明确的 greedy/rep=1 基线，与现有 Ovis 保持一致，未采用前阶段惩罚实验值。对照变化同时包含模型、词表、官方模板与任务提示词，不是只替换权重。

外层区域视觉画布仍为 32 对齐、65,536～313,600 像素，GLM 的 MNN 视觉路径随后使用 28 对齐；两模型实际视觉 token 数不同。已记录原始区域、外层画布及实际 token，不能把相同外层画布称为完全相同视觉输入。

## 真实结果

使用 Linux x86_64 VM、CPU 4 线程，真实 MNN 模型。三个扫描页区域各运行三次，未加字符串截断或诊断取消；max_new_tokens=256。

| 实际输入区域 | 原 Ovis 症状 | GLM 结果 | GLM 总时长中位数 |
| --- | --- | --- | --- |
| 第 9 题前两行，4821×398 | `2023年1月1日` 循环 | 3/3 正常停止，正文结束，无日期续写 | 5.65 秒 |
| Directions，5033×784 | 额外英文及递增空编号 | 3/3 正常停止，无额外段落/编号 | 5.75 秒 |
| Questions 标题，4776×208 | 句点循环 | 3/3 正常停止，无句点循环 | 6.64 秒 |

这些中位数包含首次运行，未执行独立预热，不是正式性能基准。vision 中位数分别约 4.57、4.00、5.03 秒，prefill 约 0.63～0.73 秒；GLM 当前主要时间仍在视觉处理。原始数据见 [comparison-summary.json](comparison-summary.json)、[region-comparison.json](region-comparison.json) 和 `glm-direct/`。

新适配后的 Ovis 在同三个区域各运行一次，原始字符串、真实 token 序列及外层视觉尺寸与先前已保存的 Ovis 对照完全一致，仍出现原异常；不是修改 Ovis 解码后再比较。

6800×9067 的用户原图完整运行一次：17 个区域全部正常停止，DocumentIR `status=ok`，未捕获此前的日期/编号/句点异常续写；总用时 **113.17 秒**，包含页面处理和版面分析。相同原图的历史 Ovis 对照为 175.56 秒并存在异常/重试；单次结果不能直接宣称稳定加速比例。见 [整页输出](user-page/user-original-run-1/document.md)、[summary](full-page-summary.json)、[report](user-page/report.json)。

真实流采集含 **186 个生成中且未返回完整结果的快照**。整页首内容约 21.77 秒（从 job 开始，包含页处理和版面分析），不等于单区域首 token。最终图片输出沿用原链路，未处理 BUG-001 的插图延迟问题。

整页进程峰值 RSS 约 **2.90 GiB**，识别后 RSS 约 **2.56 GiB**；包含模型、输入解码与诊断采集，不是 Android 独立模型占用。实际配置及 manifest 均 `use_mmap=false`、`kvcache_mmap=false`，未创建 `ovis-mmap-*` 权重缓存。GLM 不能据此宣称更省内存。

### 低像素、大字和混合内容

| 控制区域 | GLM 三次真实输出 | 结论 |
| --- | --- | --- |
| 20×8，预期“商品统计” | 均“商品设计”，正常停止 | 无循环，但**仍错识**；细节丢失未解决 |
| 692×696，来自 1 MP 单大字图，预期“识” | 均“识”，正常停止 | 本受控区域未复现循环 |

证据见 `controlled/`。未把无循环等同于识别正确，未由有限样本推导普适像素下限或根因。

既有中文/公式/表格/插图混合样本运行一次，用时 15.03 秒，9 个生成区域正常停止。实际 trace 确认 text/formula/table 分别使用对应 GLM 提示词，公式输出 `E = m c ^ {2}`，表格为 HTML 且数值 3/45、2/30 与图中一致；插图资源保留。见 [混合输出](mixed/mixed-text-formula-table-run-1/document.md) 与 [report](mixed/report.json)。此为固定样本人工对照，不是总体准确率验收。

## 本地检查与 APK

- 两版各 **74 项 JVM 测试**通过，lint 各 0 Error／11 个既有 Warning。见 [android-validation.json](android-validation.json) 与 `jvm/`。
- 原生配置测试通过：任意目录/名称、任务提示词、不同 image_pad、非法/越界工件、非法采样、浮点序列化舍入、线程/mmap 与旧 Ovis 兼容数据一致性，见 [native-config-test.json](native-config-test.json)。
- 浏览器主验收 19 项、模型页 10 项、真实流/导航及裁剪回归通过。主验收首次在模型推理并行时出现滚动坐标断言失败；无相关阅读代码改动，停止推理后原断言完整通过，未放宽断言。新模型选项使用 AndroidHost 桥模拟验证，不能当作设备推理。
- 两版 APK 构建成功；arm64 ABI、动态库/符号、权重排除、构建 ID 关联 Release/-O3/NDEBUG、裁剪/OpenCV 检查通过，见 [apk-checks.json](apk-checks.json)。签名与已发布 v0.7.6 同类型 APK 相同，见 [apk-signatures.json](apk-signatures.json)。
- 本地 APK 在 `artifacts/glm-ocr/`：`suishou-ocr-glm-config-user-arm64.apk` 和 `suishou-ocr-glm-config-lab-arm64.apk`，附 SHA256SUMS。仍为 0.7.6-ocr／13 的开发构建，**未覆盖 GitHub 发布资产，也未发布新版本**。

复现入口：

```bash
python3 tools/prepare_ocr_model.py --profile glm \
  --model-root /home/dr/project/models/GLM-OCR-MNN \
  --output verification/glm-ocr/glm-engine-config.json --threads 4
python3 tools/debug/continuation_probe.py \
  --library /tmp/glm-ocr-probe/libdococr_c_diagnostic.so \
  --engine-config verification/glm-ocr/glm-engine-config.json \
  --input verification/ocr-degeneration/user-page-20261009/captured/date-region.crop.png \
  --output /tmp/glm-ocr-date-replay --kind date --runs 3 --max-tokens 256
```

临时 probe 构建见 [probe-build.json](probe-build.json)。流时间线采用无损 gzip，原始 SHA 和大小见 [timeline-archives.json](timeline-archives.json)，可直接解压恢复。诊断工具的重复判据只用于这些固定样本，不是生产修复。

## 待验收

ADB 当前设备列表为空。Titan_1 上的加载、实际识别、流式 UI、内存与速度需后续验证；Linux 结果不能替代设备验收。整页只有一次运行，尚无全面人工标注、不同纸张/低像素来源及透印因果对照。BUG-002 不关闭，不宣称根因已确定或通用准确率已通过。
