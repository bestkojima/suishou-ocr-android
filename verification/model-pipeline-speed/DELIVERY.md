# GLM-OCR 与 OvisOCR2 完整流水线单次实测

2026-10-09，在 Linux x86_64 VirtualBox 中执行与 App 同源的当前 Android 原生适配，使用固定 MNN 3.6.1 提交 `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`、Release／-O3／NDEBUG、CPU 4 线程、greedy、mmap=false／KV mmap=false。ADB 没有连上的设备，以下是虚拟机实测。

用户随后要求用 Ovis 正常页面比较，并核实 MNN 的 `-O3`。已补充 [正常页预热后三次中位数与编译证据](normal-page/DELIVERY.md)：Ovis 11.04 s、GLM 13.36 s，全部正常且无重试；实际 MNN／LLM 库 SHA 与本页一致，CPU 内核最终参数 `-O3 -DNDEBUG`。本页异常扫描记录保留。

正常页比较后，又按用户要求 [再次真实运行本页原图](problem-page-rerun/DELIVERY.md)：Ovis 三处异常仍复现，148.43 s／partial；GLM 103.77 s／ok。两模型各区域全部尝试原始输出均与本页第一次运行逐字相同，BUG-002 未关闭。

## 本次完整执行范围

此前用户提供的英文扫描页，**6800×9067，原图模式**，输入 SHA `0f69c46def894afc60ba0d36901578d9aa65705ad3a9510274e8915fb710f88e`。采用同一原生库，先 GLM、后 Ovis，独立进程各完整运行一次，未独立预热或清空系统磁盘缓存。计时期间没有并行构建或模型推理。

从完整 PNG 内存输入重新解码 → 缩放／补白至 800×800 的版面模型画布 → PP-DocLayoutV3 检测 → 映射原图框 → 原图区域裁剪与 smartresize → 真实模型生成与实时流 → 现有重试策略 → DocumentIR／Markdown 组装 → 作业恢复。结果及全部 41 项图片／诊断资源已保存，文件落盘单列。

两次的 300 个版面候选、原图框及 selected 标记完全相同，均有 17 个生成区域。未将孤立区域识别、已有 JSON 回放或模型下载当作完整流程。此次原图模式没有均衡档 8 MP 降采样；未执行 Android Bitmap／EXIF、相机、裁剪交互或 WebView 渲染，不能声称完整 Android UI 端到端耗时。

## 完整流水线结果

| 指标 | GLM-OCR | OvisOCR2 |
| --- | ---: | ---: |
| 引擎加载（含版面模型） | 13.10 s | 2.39 s |
| 整页首内容，从识别开始 | 20.96 s | 13.58 s |
| 完整识别流水线，不含首次加载 | **135.80 s** | **148.55 s** |
| 结果和全部资源保存 | 0.025 s | 0.021 s |
| 加载＋识别＋保存计时项合计 | 148.93 s | 150.96 s |
| 识别区域／尝试次数 | 17／17 | 17／18 |
| 正常停止区域 | 17 | 14 |
| 结果状态 | ok | partial |
| 测试进程峰值 RSS | 2.91 GiB | 2.80 GiB |

本次整页 GLM 少用 **12.75 秒，约 8.58%**；用时比 Ovis／GLM 为 **1.094**。加载合计比较仅是所列计时项求和，不包含计时工具自身的流时间线压缩、初始文件读取等开销。模型加载未重复扫描权重 SHA；加载时间没有清空系统缓存后的冷启动含义。

原生 manifest 记录的阶段如下，完整壁钟还包含未归入四段的输入／资源处理和接口开销，Ovis 还包含作业末尾的恢复。四段合计不能替代完整识别壁钟。

| 原生阶段 | GLM-OCR | OvisOCR2 |
| --- | ---: | ---: |
| 图片解码 | 2.306 s | 0.285 s |
| 版面处理／检测 | 2.182 s | 0.965 s |
| 区域识别（含全部重试） | 120.172 s | 135.433 s |
| DocumentIR／Markdown 组装 | 0.178 s | 0.148 s |

## 正常区域与异常开销

从本次完整运行中取双方均正常停止的 **14 个相同区域**，排除 Ovis 异常的 reqr0009／0013／0014：

| 这 14 区域的合计 | GLM-OCR | OvisOCR2 |
| --- | ---: | ---: |
| 识别用时 | **69.316 s** | **33.273 s** |
| vision | 52.291 s | 9.154 s |
| prefill | 8.854 s | 13.690 s |
| decode | 7.434 s | 9.728 s |

这一正常区域子集里，Ovis 约为 GLM 的 **2.08 倍速度**，GLM 的视觉阶段耗时较多。它是同一次整页运行的派生数据，未重新跑单区域，也不包含整页公共步骤。模型模板、提示词、词表及实际视觉 token 不同，不能将其解释成只替换权重的纯模型计算速度对照；正常停止也不代表准确率已验收。

Ovis 的三个异常区域仍与此前扫描页现象一致：

| 区域 | 本次原始输出 | 尝试与时间 |
| --- | --- | --- |
| reqr0009，第 9 题前两行 | 正文后反复 `2023年1月1日` | 512 token 达上限，约 9.61 s |
| reqr0013，Directions | 额外生成递增空编号 | 512 token 达上限，约 9.64 s |
| reqr0014，Questions 标题 | 正文后循环句点 | 首次 512 token，约 9.55 s；重试 4096 token，约 73.36 s |

Ovis 因 partial 状态在作业末尾自动恢复引擎：日志有首次 LLM 加载 1349 ms 和恢复时加载 791 ms。恢复开销已经包含在 148.55 秒中；本页没有逐区域加载模型。stream 的 vision／prefill／decode 合计只保留每区最后一次尝试，Ovis 首次 reqr0014 的阶段不能从该合计得出，故完整比较采用含全部重试的 manifest 区域识别和壁钟。

GLM 的 reqr0014 正常输出了标题，只有 16 个输出 token，但 **prefill 为 34.96 秒**，单区总用时 39.65 秒；这次尖峰已完整计入整页结果。根因未确认，本轮没有额外诊断或改变生产参数，不据此宣称模型固有 prefill 就这么慢。

因此本页整页 GLM 略快、结果均正常停止；Ovis 在双方正常停止的区域里更快，异常续写与重试明显拖长了整页。单次结果同时受首次图形／形状初始化、系统缓存和虚拟机运行波动影响，不能当作预热三次中位数、其他图片的稳定加速比例或 Titan_1 性能验收。BUG-002 仍未关闭。

## 证据与复跑

- [comparison.json](comparison.json)：本次总体、相同区域与实际配置对照。
- [environment.json](environment.json)：来源提交、库 SHA、固定 MNN 与适配层实际编译参数。
- `glm/`、`ovis/` 各保存 benchmark、manifest、DocumentIR、Markdown、无损压缩真实流和日志。全部图片与张量资源保存在本地 `artifacts/model-pipeline-speed/{glm,ovis}/run-1/`，不加入 Git。
- 本次沿用已有 MNN Release 构建，使用 `verification/native-release/host-build/CMakeLists.txt` 重编当前适配层。临时 `/tmp/ocr-model-pipeline/reuse/mnn` 指向 `/home/dr/project/MNN/build`，`reuse/CMakeCache.txt` 指向该构建的缓存；构建记录在 `config.log`／`build.log`。

当前环境的实际命令如下。复跑时请更换输出目录，保留本次证据。

```bash
cmake -S verification/native-release/host-build -B /tmp/ocr-model-pipeline/host -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/home/dr/Android/Sdk/cmake/3.31.6/bin/ninja \
  -DCMAKE_BUILD_TYPE=Release -DMNN_SOURCE_ROOT=/home/dr/project/MNN \
  -DOCR_MNN_BUILD_ROOT=/tmp/ocr-model-pipeline/reuse
cmake --build /tmp/ocr-model-pipeline/host --target dococr_c -j 2

python3 tools/prepare_ocr_model.py --profile glm \
  --model-root /home/dr/project/models/GLM-OCR-MNN \
  --output verification/model-pipeline-speed/glm-config.json --threads 4
python3 tools/prepare_ocr_model.py --profile ovis \
  --model-root /home/dr/project/docprase/models/ovis \
  --output verification/model-pipeline-speed/ovis-config.json --threads 4

python3 tools/benchmark_ocr.py \
  --library /tmp/ocr-model-pipeline/host/docprase/libdococr_c.so \
  --engine-config verification/model-pipeline-speed/glm-config.json \
  --input verification/ocr-degeneration/user-page-20261009/source.png \
  --output artifacts/model-pipeline-speed/glm --threads 4 --runs 1 \
  --allow-partial --gzip-timeline --export-assets
python3 tools/benchmark_ocr.py \
  --library /tmp/ocr-model-pipeline/host/docprase/libdococr_c.so \
  --engine-config verification/model-pipeline-speed/ovis-config.json \
  --input verification/ocr-degeneration/user-page-20261009/source.png \
  --output artifacts/model-pipeline-speed/ovis --threads 4 --runs 1 \
  --allow-partial --gzip-timeline --export-assets

python3 tools/compare_ocr_models.py --glm artifacts/model-pipeline-speed/glm \
  --ovis artifacts/model-pipeline-speed/ovis \
  --output verification/model-pipeline-speed/comparison.json
```

计时工具只新增模型绑定、阶段／尝试统计、流无损压缩和真实资源保存；App／JNI／生产识别配置未修改。真实两次完整运行通过流与保存尝试一致、实际线程／mmap、资源导出检查；比较命令验证同输入／库／配置和实际版面候选。未因本次计时构建或发布新 APK。
