# 第三方组件

随手识别0.7.4使用以下第三方组件。模型权重在应用中另行下载，没有随APK或本仓库打包。

| 组件 | 用途 | 原许可／来源 |
| --- | --- | --- |
| docprase | 版面规划、区域识别、DocumentIR与导出 | Apache-2.0；https://github.com/bestkojima/docprase |
| MNN | CPU推理、视觉与语言模型运行时 | Apache-2.0；https://github.com/bestkojima/MNN |
| nlohmann/json | 原生JSON处理 | MIT，见native/docprase/third_party/nlohmann |
| stb_image / stb_image_write | PNG/JPEG解码和PNG保存 | 保留stb的MIT/Public Domain双许可，见源码头部与尾部 |
| React / React DOM | WebView界面 | MIT，见对应LICENSE |
| Streamdown / CJK / Math | 流式Markdown排版 | Apache-2.0，见对应LICENSE |
| KaTeX | 数学排版及打包字体 | MIT，见对应LICENSE |
| fflate | ZIP导入／导出 | MIT，见对应LICENSE |
| Apache POI / poi-scratchpad | Office文档解析 | Apache-2.0；https://poi.apache.org/ |
| pdfbox-android / Apache PDFBox | PDF内容提取 | Apache-2.0；https://github.com/TomRoush/PdfBox-Android |

许可证文本随APK放在assets/legal中；Apache-2.0全文亦用于POI/PDFBox等Apache组件。原生源码、MNN依赖以及npm/Maven包保留各自的版权声明与许可证。下载模型的来源和工件身份见app/src/main/assets/ocr/models.json与模型仓库说明；下载成功不代表任意模型已适配。
