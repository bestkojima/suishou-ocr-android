# OCR 流式渲染验证原型

这是可丢弃的验证原型，不是安卓应用。验证问题：真实 DocumentIR 中的正文、HTML 表格、公式与图片逐步显示时，已经阅读的内容是否稳定。

直接打开 `dist/ocr-streaming-prototype.html` 即可使用。它包含所有脚本、真实图片和数学字体，运行时不请求外网。打开后选择样本和图片场景，点击“开始回放”；可暂停、切换横竖屏、上滑查看历史内容、点击图片放大，以及在失败场景中重试图片。

重新构建：

```bash
cd /home/dr/project/android_ocr/prototypes/ocr-streaming
npm ci
npm run build
npm test
```

本地浏览器预览：`npm start`，访问 `http://127.0.0.1:4173/`。

## 样本与原始资源

- `fixtures/odb-13`：中文物理题，16 个区域，1 张 HTML 表格，4 张插图。
- `fixtures/odb-09`：三角公式，10 个区域，5 个公式区域，1 张图片；原始结果含 partial 状态。
- 来源为 `/home/dr/project/docprase/output/issue-27-final/development/`；每个样本的 `provenance.json` 记录原始路径、JSON 与图片 SHA-256。源仓库没有被修改。
- `npm run fixtures` 可从本机原始结果重新准备样本。原始输入和资源会与历史 `command.json` 中的 SHA-256 核对。
- 原始 JSON 与 Markdown 保留不变。展示层按 `reading_order` 读取最终 blocks，将图片资源转换为 Markdown 图片引用，将 `latex` 内容包入数学分隔符。

## 实际接入的组件

Streamdown 2.7.0、React、`@streamdown/math`、`@streamdown/cjk`、KaTeX。精确依赖见 `package-lock.json`；KaTeX CSS/字体与公式插件的 KaTeX 引擎保持同版。

图片引用通过受控的本地资源清单映射，不向远端发起请求。单文件版将图片嵌入，JSON/PNG 原件仍保留在 fixtures 中。生产 Android 中应替换为应用内文件服务，避免将完整大文档的图片全部转为 base64 常驻内存。

## 稳定显示策略

- 真实结果按区域拆成模拟字符增量，不伪称为真实 token 事件。
- 完成区域使用稳定 ID 和 React memo；仅活动区域继续更新。
- 表格、公式完整后提交；未完成的内联数学表达式缓冲。表格此次按整块提交，不是逐行提交。
- 图片在发起加载前按资源尺寸预留空间，失败占位和重试保持同样尺寸。
- 数学字体预加载，关闭逐字动画。宽表格和块公式可横向滚动。
- 上滑后停止跟随，点击“回到最新”恢复；旋转使用区域 ID 和块内相对阅读位置恢复，不重新执行任务。

## 验证与限制

`npm test` 使用 Playwright 和本机 Chromium。默认路径在 `verify.mjs`，可用 `CHROMIUM_PATH` 指向其他已安装 Chromium。结果在 `verification/report.json`，截图在同目录。

验证图片加载、流式暂停、完整内容一致性、公式/表格显示、已完成区域 DOM 复用、图片延迟/重试的几何位移、阅读滚动和横竖屏切换。报告里的位移只对应这些固定样本和场景，不代表任意文档永远零跳动；当前未完成区域仍可能正常换行。

尚未验证 Android WebView、C++/JNI 增量回调、真实推理性能、超长文档内存、复杂跨区域引用和进程重启恢复。旋转保留块内相对位置，尚非精确字符锚点。此处的 JSON 是历史最终结果，不能证明引擎可在识别过程中输出相同内容或提前固定阅读顺序。

源代码和原型保留在当前工作区供复验；当前工作区没有 Git 仓库，因此未创建原型分支或提交。
