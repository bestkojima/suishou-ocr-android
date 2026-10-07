# 01：从真实模型输出流发布正文

Status: ready-for-agent

- [x] 用真实 ostream/streambuf 适配替换区域级 ostringstream 缓冲，最终 raw_output 保留。
- [x] 作业绑定在同步 run 线程；累计快照支持区域重试、UTF-8 拆分、异常结束和销毁清理。
- [x] 通过实际模型证明同步 run 与区域完成前已有正文；九个区域的最终流式原文与 DocumentIR 的真实 attempt 输出一致。
- [x] 实际 JNI 使用标准 UTF-8 解码快照，保留 Alpha PNG 输入修复。

## Comments

2026-10-07：相邻 docprase/MNN 源码未修改；Android 源码生成层精确替换，新的流模块属于本项目。累计正文独立于最多 64 条的进度事件队列，避免慢轮询丢失内容。真机链路仍待设备连接后验证。
