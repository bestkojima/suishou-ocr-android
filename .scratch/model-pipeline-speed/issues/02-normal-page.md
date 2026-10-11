# 正常页面对照与实际 MNN 优化编译证据

Status: ready-for-agent

用户补充要求：使用 Ovis 能正常处理的页面比较，并检查前次 MNN 是否真的采用 `-O3`。

- [x] 确认实际加载的 MNN／LLM 库路径、SHA 及 CPU 内核和适配层生效编译参数，与前次运行使用的库对应。
- [x] 使用已有正常混合内容样页，同库、同版面模型、CPU 4 线程、mmap=false，各模型预热一次后完整计时三次。
- [x] 确认每次均正常完成、没有异常续写或重试，保存实际流、DocumentIR、Markdown 和图片资源。
- [x] 报告中位数、首内容和各阶段耗时；保留旧异常页面结果，明确 Linux／真机及正常停止／内容正确的界限。

## Comments

2026-10-09：开始。样页为 `verification/ticket5/source.png`，1000×1380，与历史 Release＋mmap=false 下 Ovis 两次正常结果输入 SHA 相同。本次只补充计时与编译证据，不修改应用生产策略或发布 APK。诊断技能仅用于实际差分计时和编译配置核实；本次未要求修复，因此不进入修改模型、根因确认或修复流程。

2026-10-09：完成。Ovis 11.045 s／GLM 13.361 s，Ovis 少用 17.34% 时间；首正文 2.121 s／2.070 s。预热＋三次计时全部 normal、无重试，各 27 项资源导出，Markdown SHA 稳定。前次实际异常扫描页已用于检查拒绝信号，本次正常页检查通过。实际库哈希及 19 个 MNN 相关目标／23 个适配翻译单元编译证据保存在 `verification/model-pipeline-speed/normal-page/`；BUG-002 未关闭。
