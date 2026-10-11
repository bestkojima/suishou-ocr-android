# 正常页面：OvisOCR2 与 GLM-OCR 完整流水线对照

2026-10-09，按用户补充要求，改用 Ovis 能正常完成的页面重新运行。**上一次完整流水线和本次实际使用的 MNN 都已启用 Release／`-O3 -DNDEBUG`**；库 SHA 相同。正常页预热后，Ovis 整页中位数 **11.04 s**，GLM **13.36 s**，Ovis 少用 **2.32 s／17.34%**，GLM 用时为 Ovis 的 **1.21 倍**。

## 输入与测量方式

输入是已有 [混合内容样页](../../ticket5/source.png)，1000×1380，含中文正文、公式、表格和插图，SHA `c71ed55fd66f249cc6f2435f7986961e1065a804aee57665e48858455143a46d`。该页在历史 Release＋mmap=false 的 Ovis 两次记录中均为 ok，本轮也完整正常结束。它不是此前出现异常续写的英文扫描页；[旧页结果](../DELIVERY.md) 保留。

同一固定 MNN、同一 Android 源码适配层 Linux 原生库、同一 PP-DocLayoutV3、CPU 4 线程、greedy、use_mmap=false、kvcache_mmap=false。各自沿用模型目录配置的提示词和视觉参数，未调整生产采样、像素预算或重试策略。

顺序先 Ovis、后 GLM，各用独立进程加载一次；每模型 **1 次完整预热＋3 次完整计时**，计时复用同一引擎。没有并行模型推理／编译，没有清空 OS 磁盘缓存，也没有重新扫描权重 SHA。

每次从完整 PNG 内存输入开始，重新解码→800×800 画布版面检测→映射原图区域→裁剪与视觉适配→真实生成流→DocumentIR／Markdown 组装。两模型四次运行的版面候选、原图框和 selected 标记一致，各 **9 个生成区域、10 个结果块**。预热和全部计时均 ok、normal，无重试；每次导出 27 项资源，真实生成与保存的原始尝试一致。进度日志只打印了 8 个区域，最后一个区域完成与作业返回之间没有被轮询打印；最终快照和 manifest 均含 9 区，不据进度打印行数统计。

## 结果

除加载和预热外，下表均为三次计时的中位数。完整流水线不含首次加载、时间线压缩、文件落盘及 Android UI 渲染。

| 指标 | OvisOCR2 | GLM-OCR |
| --- | ---: | ---: |
| 完整流水线 | **11.045 s** | **13.361 s** |
| 首个正文可见，从流水线开始 | 2.121 s | 2.070 s |
| 解码 | 0.001 s | 0.001 s |
| 版面处理／检测 | 0.560 s | 0.520 s |
| 全部区域识别 | 10.367 s | 12.642 s |
| vision，9 区合计 | 1.194 s | 8.829 s |
| prefill，9 区合计 | 6.918 s | 1.861 s |
| decode，9 区合计 | 2.089 s | 1.728 s |
| DocumentIR／Markdown 组装 | 0.003 s | 0.003 s |
| 文件与全部资源落盘 | 0.014 s | 0.015 s |
| 输出 token 数 | 150 | 143 |
| 识别区域／尝试 | 9／9 | 9／9 |

本轮 GLM 的视觉阶段多用时间，prefill 和 decode 较快，合计仍比 Ovis 慢约 2.32 s。此为两个模型当前配置下的实际流水线差异，不能仅由阶段计时断言某个实现的根因。阶段各自取中位数，不保证相加等于整页中位数；无重试，所以本页阶段合计包含全部生成尝试。

| 作业 | Ovis 整页 | GLM 整页 | Ovis 首正文 | GLM 首正文 |
| --- | ---: | ---: | ---: | ---: |
| 计时 1（run-2） | 11.565 s | 13.772 s | 2.121 s | 2.760 s |
| 计时 2（run-3） | 11.045 s | 13.361 s | 2.361 s | 2.070 s |
| 计时 3（run-4） | 10.950 s | 12.779 s | 1.636 s | 1.950 s |

首正文由约 150 ms 一次的真实流快照中第一次出现非空原始正文得到，包含轮询时已很快完成的区域，符合能显示内容的时点。原计时工具的 `firstLiveSeconds` 要求该区域尚未完成，仍用于验证生成期间能看到片段，但会漏掉已经完成的首区；该严格值另保留于 comparison.json 的 `firstPartialRegionObservedSeconds`，不与本表首正文混用。没有用生成后的回放计算任何上述时间。

加载各仅计一次（包括版面模型）：Ovis 11.859 s，GLM 12.919 s；完整预热作业分别 16.213 s、15.154 s。加载没有重复全量校验权重，但没有清空系统缓存，不能与此前 2.39 s／13.10 s 的单次加载直接当作冷启动性能变化。测试进程最后记录的峰值 RSS 分别约 2.34 GiB／2.55 GiB，包含输入、模型、真实流采集和导出，并非 Android 模型独立内存。

## 实际 MNN 编译证据

[native-release-evidence.json](native-release-evidence.json) 保存动态加载后的 `/proc/self/maps` 实际路径、SHA 和 ELF Build ID，并与 [前次环境记录](../environment.json) 的 MNN、LLM、适配库逐项比较。

| 实际库 | 本轮与前次 SHA256 |
| --- | --- |
| `/home/dr/project/MNN/build/libMNN.so` | `3dfe05cd51a3916db5da236d44a08f945b4054cfaae7ee1d88d0cc30b7655ded` |
| `/home/dr/project/MNN/build/libllm.so` | `6350e3461088e4531a677db83ee2a54001587e10e228b563423d5623ffc4dd01` |
| `/tmp/ocr-model-pipeline/host/docprase/libdococr_c.so` | `826a40b086eed0181f9cb99230ffc4a4513959d5f183bb6a7a2d9f116cae72e4` |

固定 MNN 提交仍为 `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`。现有 MNN 构建的 `CMAKE_BUILD_TYPE=Release`；已逐项检查 19 个相关目标的实际生成编译参数，包括 MNNCPU、SSE／AVX、MNNCore、llm、Express 和 OpenCV，最后生效优化参数都是 **`-O3`**，定义 **`NDEBUG`**，没有 **`MNN_DEBUG`／`DEBUG`**。记录在 [compile-flags/](compile-flags/)，其中可直接查看 [MNNCPU](compile-flags/MNNCPU.flags.make) 和 [llm](compile-flags/llm.flags.make)。适配层／docprase 的全部 23 个翻译单元同样通过检查，命令保存在 [compile_commands.json](compile_commands.json)。

本轮复用这些库，未重编模型引擎；因此前次结果受到异常区域／重试和单次运行条件影响，不能把其速度归因于未开启 `-O3`。

## 内容与适用范围

查看 [Ovis 输出](ovis/run-4.md) 和 [GLM 输出](glm/run-4.md)，正文、能量公式、表格的商品／数值和图注均与样页对应；两模型在标题 Markdown 层级、公式写法、图注空格上有差异。各模型四次输出的 Markdown SHA 稳定。本页没有观察到用户描述的异常续写，但这不是规模化准确率验收，也不代表 BUG-002 已解决。

此次仅是 Linux x86_64 VirtualBox 的同源原生完整流水线，未运行 Android Bitmap／EXIF、相机／裁剪交互或 WebView 渲染。不能代替 Titan_1 速度／内存验收，也不能把 17.34% 外推到所有页面。

## 证据与复跑

- [comparison.json](comparison.json)：每次数据、各项中位数、全部正常检查与相同比较范围。
- [environment.json](environment.json)：输入、执行顺序、工具和模型运行配置 SHA。
- [checks.txt](checks.txt)：正常页检查通过；用前次真实异常扫描页验证，该检查会拒绝 abnormal／partial 结果。
- `ovis/` 和 `glm/`：四次 benchmark、manifest、DocumentIR、Markdown、压缩真实流及原生日志。Markdown 引用的插图已一并保留。
- 全部像素和诊断资源在本地 `artifacts/model-pipeline-speed/normal-page/{ovis,glm}/run-{1..4}/`，不加入 Git。

复跑时更换输出目录，避免覆盖本次原始证据。模型配置已准备，无须重新扫描权重校验。

```bash
python3 tools/benchmark_ocr.py \
  --library /tmp/ocr-model-pipeline/host/docprase/libdococr_c.so \
  --engine-config verification/model-pipeline-speed/ovis-config.json \
  --input verification/ticket5/source.png \
  --output artifacts/model-pipeline-speed/normal-page/ovis \
  --threads 4 --runs 4 --gzip-timeline --export-assets
python3 tools/benchmark_ocr.py \
  --library /tmp/ocr-model-pipeline/host/docprase/libdococr_c.so \
  --engine-config verification/model-pipeline-speed/glm-config.json \
  --input verification/ticket5/source.png \
  --output artifacts/model-pipeline-speed/normal-page/glm \
  --threads 4 --runs 4 --gzip-timeline --export-assets
python3 tools/compare_ocr_models.py \
  --ovis artifacts/model-pipeline-speed/normal-page/ovis \
  --glm artifacts/model-pipeline-speed/normal-page/glm --warmup-runs 1 \
  --output verification/model-pipeline-speed/normal-page/comparison.json
```

本次仅扩展计时／对照工具及证据，没有修改 App、生产模型配置或发布 APK。
