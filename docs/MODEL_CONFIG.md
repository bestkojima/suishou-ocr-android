# 模型目录配置驱动的 OCR

在“设置 → 识别模型”选择 OvisOCR2 或 GLM-OCR，下载该模型及共同的版面分析模型，随后加载。Ovis 是默认选择；切换模型会先卸载空闲引擎，识别/加载或下载期间禁止切换。连续识别仍复用已加载模型。

## 配置文件

模型在 App 私有 `models/{repo}/files/` 内。原始 `config.json`、`llm_config.json` 和权重保持下载校验契约。首次准备在同目录生成 `ocr_runtime.json`：源配置已有 `ocr` 时保留其字段；否则从 `app/src/main/assets/ocr/model-profiles.json` 的数据预设补充。

适配读取模型目录配置，目录名字不决定识别模型。下载、界面选项和文件清单仍需登记配套工件；任意公开仓库下载成功不表示已验证兼容。

| 文件/字段 | 用途 |
| --- | --- |
| `ocr_runtime.json` 的 `llm_model`、`llm_weight`、`visual_model`、`visual_weight` | 配套语言/视觉模型文件 |
| `tokenizer_file`、可选 `embedding_file`、`llm_config` | 词表、独立 embedding 和结构配置文件 |
| `ocr.name` | 实际模型名称，写入引擎 profile 与 manifest |
| `ocr.prompts.text/formula/table` | 对应识别任务的提示词，缺少公式/表格字段时回退 text |
| `sampler_type` 与 `repetition_penalty` 等 | 固定 MNN 的运行采样参数；greedy 不应用重复惩罚 |
| `image_min_pixels`、`image_max_pixels` | MNN 内部视觉预处理范围 |
| `llm_config.json` 的 `model_type`、`is_visual`、`image_pad`、`jinja`、`image_mean/norm` 等 | 模型结构、图像占位 token、聊天模板及归一化 |

GLM 的运行配置核心字段如下；完整文件还保留下载配置中的精度、内存等字段：

```json
{
  "llm_model": "llm.mnn",
  "llm_weight": "llm.mnn.weight",
  "visual_model": "visual.mnn",
  "visual_weight": "visual.mnn.weight",
  "llm_config": "llm_config.json",
  "tokenizer_file": "tokenizer.txt",
  "embedding_file": "embeddings_bf16.bin",
  "sampler_type": "greedy",
  "repetition_penalty": 1.0,
  "image_min_pixels": 12544,
  "image_max_pixels": 9633792,
  "ocr": {
    "name": "GLM-OCR",
    "prompts": {
      "text": "Text Recognition:",
      "formula": "Formula Recognition:",
      "table": "Table Recognition:"
    }
  }
}
```

官方 GLM `llm_config.json` 的图像占位 token 为 59280，包含 GLM 聊天模板及 EOS；应用直接读取它，不写死到后端。模板/tokenizer/权重必须配套。源下载配置的旧字段 `penalty:1.1` 不等于固定 MNN 的 `repetition_penalty`。本轮预设使用 greedy 基线，不把惩罚实验当成默认修复。

## 配置优先级与约束

1. 优先读取已绑定的 `ocr_runtime.json`，否则读取 `config.json`；只有原固定 Ovis 源配置支持旧目录兼容。
2. 按固定 MNN 的顺序合并 `llm_config.json`，再校验最终文件引用。模型文件须属于同目录已登记工件，缺失、跨目录引用或非法采样名称明确报错。
3. 应用最后固定 CPU/所选线程、关闭 KV 复用和 prompt cache、`use_mmap=false`、`kvcache_mmap=false`，并在加载前通过 `set_config` 再设置有效配置。用户字段不能开启 mmap；临时目录由 App 提供。
4. 加载后比较实际配置与请求配置，小数只容忍固定 MNN 的 float 序列化舍入。manifest 记录实际模型字段；生成时的 token 证据可进一步验证实际配置。

`source_config_sha256` 只用于判断何时重新初始化运行配置；不要改它。原始配置未改变时，用户对 `ocr_runtime.json` 的修改会保留。重新下载改变源配置后会重建运行配置。修改后应先卸载/重启应用再加载，缓存引擎不进行区域间热更新。

完整大权重 SHA 仅在下载完成时校验。加载检查既有记录和文件元数据，运行配置只计算小文件 SHA。旧 mmap 缓存保留，不复用或删除；加载失败不自动开启 mmap。

## 视觉处理与接口兼容

版面分析仍使用 800×800。识别区域仍从原图裁剪，现有外层视觉画布保持 32 对齐及 65,536～313,600 像素；GLM 的 MNN 视觉路径随后按其 28 对齐规则处理。因此同区域输入不保证两模型最终视觉尺寸/token 数相同，也不是只更换权重的实验。

公共 backend 标识仍沿用 `mnn:pp-doclayout-v3+ovisocr2` 兼容别名，数据格式不变；实际模型由 profile 和 `runtime_configuration.model_name/model_type/image_pad` 区分。文档页面显示中性的本地识别流程，避免 GLM 被标成 Ovis。

Linux 模型准备示例（工件应先完成下载校验）：

```bash
python3 tools/prepare_ocr_model.py --profile glm \
  --model-root /home/dr/project/models/GLM-OCR-MNN \
  --output verification/glm-ocr/glm-engine-config.json --threads 4
```

本轮真实结果见 [GLM-OCR 交付记录](../verification/glm-ocr/DELIVERY.md)。Linux 运行与浏览器桥模拟不代表 Titan_1 已验收；不能据此声明通用准确率或关闭重复生成 bug。
