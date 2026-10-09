# 模型目录配置驱动的 GLM-OCR 适配与真实对照

Status: ready-for-agent

2026-10-09 用户要求用 GLM-OCR 测试已提供的异常扫描页，并明确适配由模型文件夹内的 config 字段驱动。

模型工件、模板、图像 token、任务提示词和视觉参数从模型目录配置读取，不依据目录名称判断模型。保留固定 MNN、CPU 线程约定及 use_mmap=false；下载完成校验权重，加载不重新扫描。旧 Ovis 仍可用。实现后在相同问题区域及整页上运行真实模型，记录异常续写、停止原因及内容差异，不以加载成功代替 OCR 验收。

## Tasks

- [x] [01 配置驱动适配](issues/01-config-adapter.md)
- [x] [02 GLM 真实识别对照](issues/02-real-comparison.md)

## Comments

2026-10-09：开始。当前加载器固定 Ovis 工件、提示词及 image_pad；固定 MNN 源码已包含 GLM-OCR 导出支持。未在现有模型目录找到 GLM 工件，已询问用户路径，同时检查 MNN 团队的 taobao-mnn/GLM-OCR-MNN 固定提交。

2026-10-09：已完成。模型目录字段驱动 GLM/Ovis 加载，两版构建、各 74 项 JVM、lint、原生与界面检查通过。用户扫描页三个异常区域各 3/3 正常停止，整页 17 区域正常；低像素仍错识。未发布新版，BUG-002 与设备验收保留。证据见 [GLM-OCR 交付记录](../../verification/glm-ocr/DELIVERY.md)。
