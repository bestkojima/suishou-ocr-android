# docprase 页面流水线接入与发布

Status: ready-for-agent

用户要求删除 core 转发层，以模块和 pipeline 完成页面处理，并将本轮源码接入 App、构建 APK、发布及上传代码。保留公共 run_page 与 C ABI，普通版和测试版沿用签名、应用标识、固定 MNN、Release/-O3/NDEBUG 和 mmap=false。发布为 0.7.8-ocr／15 预发布。上传成功后停止，不查询远端资产或下载复核。

设备端 OCR、性能与内存仍待真机验收；不宣称 PP-Structure 已实现或 BUG-002 已修复。
