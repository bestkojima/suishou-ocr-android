# 结果回放样本

这里是 App 测试模式、浏览器预览和回归检查共用的样本源；它们是历史 DocumentIR 结果，不代表本轮真实推理。

- `odb-13`：中文物理题，16 个区域、表格和插图。
- `odb-09`：三角公式，10 个区域，保留原始 partial 状态。
- 每份样本保留 `document.json`、`document.md`、原图、`assets/` 和 `provenance.json`。来源路径与 SHA-256 是历史证据，迁移时不改写。

`npm run build:web` 从这些本地文件生成忽略跟踪的 `web/generated/replay-fixtures.json`，校验 JSON、原图及图片的来源 SHA，再打包浏览器资源。构建不依赖相邻 docprase 仓库或网络。Android 内部仍使用 `fixtures/odb-13`、`fixtures/odb-09` 路径。

更新样本时须同步来源记录；不手改生成 JSON。旧原型界面已退役，截图和报告见 [历史原型证据](../../verification/streaming-prototype/README.md)。
