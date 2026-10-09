# 一键构建、提交、发布及使用说明

Status: ready-for-agent

- [x] 提供 `tools/release.py`，递增版本、生成说明、构建并检查两版、提交／推送／上传三项资产。
- [x] 默认预发布，上传成功后不查询、不下载、不复核；本地 SHA 在上传前生成。
- [x] 保留固定 MNN、原生 Release、应用标识／签名；失败停止，提交后可续传。
- [x] README、专用文档和 MEMORY 记录入口及新的上传后约定。
- [x] 在临时仓库验证正常流程、失败停止、续传、dry-run 和版本约束，不创建真实 release。

## Comments

2026-10-09：开始实施，当前源码版本保持 0.7.7-ocr／14。

2026-10-09：已完成，12 项流程测试及当前项目 dry-run 通过。成功后的最后一条外部命令为 GitHub 发布命令，之后只保存本地状态并报告地址；用户的“不复核”约定已记录到 MEMORY。使用说明：`docs/RELEASE.md`；证据：`verification/release-script/DELIVERY.md`。
