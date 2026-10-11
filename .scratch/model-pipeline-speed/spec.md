# GLM-OCR 与 OvisOCR2 完整流水线计时

Status: ready-for-agent

用户要求用完整流水线重新实际运行并比较两模型速度。采用此前提供的 6800×9067 英文扫描原图，原图模式、CPU 4 线程、当前生产配置、Release 和 mmap=false，各模型运行一遍。版面模型相同，从完整输入重新检测、裁剪、识别、重试并组装 DocumentIR／Markdown，不采用孤立区域或已有 JSON 回放。

ADB 当前没有设备，采用同源 Android 原生适配的 Linux 完整推理，不能称为 Titan_1 性能验收。本轮只测量，不改采样、prompt、分辨率预算、重试或发布 APK。记录加载、整页首内容、解码、版面、识别、输出组装、区域／重试和内存；单次不得称为预热三次的中位数。

## Tasks

- [x] [01 完整运行与计时报告](issues/01-full-pipeline.md)
- [x] [02 正常页面对照与 MNN 编译证据](issues/02-normal-page.md)
- [x] [03 再次对照异常英文扫描页](issues/03-problem-page-rerun.md)

## Comments

2026-10-09：开始。模型和输入仍在，旧临时库已清理；复用固定提交的 Release MNN 重编当前 Android 适配。

2026-10-09：已完成。GLM 135.80 s／ok，Ovis 148.55 s／partial；17 个生成区域和实际版面候选一致。Ovis 3 区异常、1 区重试，GLM 第 14 区 prefill 有 35 s 尖峰。双方正常 14 区子集 Ovis 33.273 s、GLM 69.316 s；仅报告本次数据，不改生产策略。证据见 `verification/model-pipeline-speed/DELIVERY.md`。

2026-10-09：补充正常混合样页，各预热一次、计时三次，9 区均正常无重试。整页中位数 Ovis 11.045 s／GLM 13.361 s；实际加载的 MNN／LLM／适配库 SHA 与前次相同，已检查 CPU 内核与全部适配翻译单元最后生效参数为 -O3、NDEBUG，无 DEBUG。详见 `verification/model-pipeline-speed/normal-page/DELIVERY.md`。

2026-10-09：按用户要求再次完整运行此前异常原图。Ovis 148.434 s／partial，日期／空编号／句点异常全部重现；GLM 103.766 s／ok、17 区正常无重试。全部区域原始输出与前次逐字相同，BUG-002 未关闭。详见 `verification/model-pipeline-speed/problem-page-rerun/DELIVERY.md`。
