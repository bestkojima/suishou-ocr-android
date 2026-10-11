# Ovis 异常英文扫描页再次完整对照

2026-10-09，按用户要求将之前 Ovis 会出错的原图再次真实运行。**Ovis 仍出现同样三处异常；GLM 本次没有复现这三种续写。** 两模型各区域、各尝试的全部原始输出均与前次完整对照逐字相同，没有因正常样页测试或 `-O3` 核实而发生内容变化。BUG-002 未修复、未关闭。

## 本次条件

- 原始英文扫描页 6800×9067，SHA256 `0f69c46def894afc60ba0d36901578d9aa65705ad3a9510274e8915fb710f88e`，不是聊天预览或缩小图。
- 与前次及正常页使用相同实际动态库：固定 MNN `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`，Release／`-O3 -DNDEBUG`。本次进程加载路径和 SHA 也已逐项对应 [编译证据](../normal-page/native-release-evidence.json)。
- 同一 PP-DocLayoutV3、CPU 4 线程、use_mmap=false、kvcache_mmap=false；模型目录小配置 SHA 与正常页一致，整个引擎绑定配置与前次异常页一致。加载前未重复扫描权重 SHA，未创建 mmap 权重缓存目录。
- 先 GLM、后 Ovis，独立进程各完整运行一次；不额外预热、不清空 OS 缓存、不改采样／提示词／像素预算／重试，不并行运行模型。

完整 PNG 解码→800×800 版面检测→映射原图框→区域裁剪／视觉适配→真实模型生成及实时流→既有重试→DocumentIR／Markdown 组装及作业恢复，全部实际执行。两模型 300 个版面候选、原图区域框、selected 标记相同，均有 17 个生成区域；各保存全部 41 项图片／诊断资源。没有采用孤立区域或 JSON 回放替代此次推理。

## 异常检查

检查原始流及 manifest 的所有生成尝试，不以最终导出中的过滤结果判断有无错误。固定区域判据沿用此前诊断，已覆盖三处实际可见的正文结束句；全页其他区域也执行短字符串持续重复扫描。

| 固定区域 | Ovis 本次原始生成 | GLM 本次原始生成 |
| --- | --- | --- |
| 第 9 题前两行，reqr0009 | `W: I did?` 后无依据重复 `2023年1月1日` **48 次**，512 token 上限 | 正文正常停止，无日期续写 |
| Directions，reqr0013 | 正文后额外英文与递增空编号 **1.～107.**，512 token 上限 | 正文正常停止，无空编号续写 |
| Questions 标题，reqr0014 | 首次标题后 **248 个句点**；4096 token 重试后仍 **2040 个句点** | 标题正常停止，无句点续写 |

这里的句点计数只包括标题结束后的异常尾部；通用短字符串扫描会额外把标题自身的终止句点计入，故其 compact 重复计数为 249／2041，二者不矛盾。

Ovis 这些异常在同步作业返回之前就已进入真实流：约 35.19 s 捕获日期循环，51.13 s 捕获至少 12 行连续空编号，60.20 s 捕获首次句点循环，69.74 s 捕获重试的句点循环。完整作业至 148.43 s 才返回，异常不是生成完成后的界面回放造成的。最终导出将三个失败区域替换为校对提示／原图链接，原始异常仍在证据中保留。

GLM 三处均覆盖可见结束句并正常停止，全页未命中本组短字符串重复判据。对照 [GLM 最终正文](glm/run-1.md) 与此前扫描页，没有观察到本轮新增的日期／编号／句点续写。这不等于所有字符均准确，也不是规模化准确率验收或 BUG-002 的通用修复证明。

## 完整流水线计时

这是各模型一次新运行，不是预热后三次中位数；计时不含首次加载及文件保存。

| 指标 | OvisOCR2 | GLM-OCR |
| --- | ---: | ---: |
| 完整流水线 | **148.434 s** | **103.766 s** |
| 加载，含版面模型 | 3.377 s | 6.049 s |
| 首个未完成区域的正文片段可见 | 14.559 s | 20.138 s |
| 正常停止区域 | 14／17 | 17／17 |
| 全部生成尝试 | 18 | 17 |
| 重试区域 | reqr0014 | 无 |
| 文档状态 | partial | ok |
| 解码 | 0.244 s | 1.635 s |
| 版面处理／检测 | 0.962 s | 2.164 s |
| 识别，含全部重试 | 135.138 s | 89.165 s |
| DocumentIR／Markdown 组装 | 0.141 s | 0.148 s |

本次 GLM 整页少用 44.67 s，约 30.09%。Ovis 的标题首次尝试约 10.07 s，重试约 72.15 s；异常及现有重试拖长了整页。Ovis partial 后的引擎恢复也含在完整计时中。仅取双方正常停止的相同 14 区，Ovis 合计 **33.486 s**、GLM **72.132 s**，仍不能由异常页整页用时宣称 GLM 在正常区域普遍更快。

前次 GLM 整页 135.80 s，本次 103.77 s；前次 reqr0014 的 prefill 尖峰 34.96 s，本次为 0.783 s，输出逐字相同。该尖峰本轮未再出现，原因仍未确认；运行缓存和虚拟机条件未受控，不能将时间变化解释为本轮修复或稳定提速。Ovis 前次 148.55 s、本次 148.43 s，异常内容和各尝试都保持一致。

以上为 Linux x86_64 VirtualBox 同源原生流水线，未运行 Android 相机、Bitmap／EXIF、裁剪交互或 WebView 渲染，不能代替 Titan_1 的速度／内存／识别验收。背景透印、视觉尺度、采样与模型结束机制仍只是可能解释，本轮未新增因果实验。

## 证据与重复检查

- [comparison.json](comparison.json)：同输入／实际库／版面／区域检查、全流程和正常区域子集。
- [environment.json](environment.json)：配置与实际库 SHA、执行顺序、各区域原始输出与前次逐字一致的记录。
- [Ovis 异常判定与原始尾部](ovis-anomalies.json)、[GLM 判定](glm-anomalies.json)：包含所有相关尝试、token 预算及异常首次出现在真实流的时间。
- [checks.txt](checks.txt)：资源、流／保存一致、原生加载与正常页拒绝检查。
- `ovis/`、`glm/`：本次 benchmark、manifest、DocumentIR、Markdown、压缩真实流和日志。全部资源在本地 `artifacts/model-pipeline-speed/problem-page-rerun/{ovis,glm}/run-1/`，Markdown 引用的原图区域也已复制。

从本次新运行结果重复执行固定扫描页检查：

```bash
python3 tools/debug/check_page_continuation.py \
  --folder artifacts/model-pipeline-speed/problem-page-rerun/glm \
  --output verification/model-pipeline-speed/problem-page-rerun/glm-anomalies.json
# NOT_REPRODUCED ok，退出 0
python3 tools/debug/check_page_continuation.py \
  --folder artifacts/model-pipeline-speed/problem-page-rerun/ovis \
  --output verification/model-pipeline-speed/problem-page-rerun/ovis-anomalies.json
# ABNORMAL_CONTINUATION partial，退出 1：捕获已知错误，不是检查工具运行失败
```

完整推理使用 `tools/benchmark_ocr.py`，两模型分别传入 `../glm-config.json`／`../ovis-config.json` 对应的引擎绑定、用户原图、`--threads 4 --runs 1 --allow-partial --gzip-timeline --export-assets`；复跑需另选输出目录保留历史证据。新检查脚本只用于固定图片的已知症状，不写入生产字符串截断策略。本轮没有修改 App 或发布 APK。
