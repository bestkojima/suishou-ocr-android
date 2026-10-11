# 原生依赖

`docprase/` 保存 0.7.8 使用的模块化页面流水线源码快照，公共 `run_page` 直接由 `page_pipeline.cpp` 实现，已删除 `core.cpp` 转发文件。源仓库：https://github.com/bestkojima/docprase 。来源基线、各文件 SHA-256 和固定 MNN 提交见 dependencies.json。保留源 LICENSE 与第三方许可证；Android 适配在 app/src/main/cpp 中生成，不修改快照源码。此目录不包含模型、原生测试集或上游文档。

MNN 从 https://github.com/bestkojima/MNN.git 的固定提交获取，不随本仓库重复提交。运行 `python tools/setup_native_deps.py` 安装到忽略跟踪的 native/MNN。Android 只使用公开引擎源代码。
