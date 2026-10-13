# 退役原型构建链并标记重复流程

Status: ready-for-agent

来源：[规格](../spec.md)。

验收：正式代码和构建不依赖旧原型目录；样本迁移字节一致；生成的浏览器数据与旧 JSON 等价；现有界面回归通过；Gradle 正确打包新目录并跟踪输出。重复流程说明见 [结构维护](../../../docs/STRUCTURE.md)。

## Comments

已完成原型代码清理、样本/状态机/历史证据迁移，以及生成数据与 Gradle 构建入口收拢。验证完成，结果如下。

- 删除 9 个过时原型文件，共 3,355,097 字节；新样本生成器 2,909 字节。原型 npm 依赖与 dist 缓存也已清理，此数字不包含缓存及新文档。
- 34 份原始样本文件、5 份历史截图/报告迁移后逐字节一致；见 [迁移记录](../../../verification/structure-cleanup/migration.json)。
- 生成的浏览器样本与迁移前 JSON 深度比较完全相等，包含原图、全部资源、区域和 provenance。
- `OCR_EVIDENCE_DIR=verification/structure-cleanup/web npm run test:web`：19/19 通过，无未捕获异常和第三方请求；见 [报告](../../../verification/structure-cleanup/web/report.json)。本轮保留报告，不重复保存截图。
- `./gradlew :app:bundleWeb :app:mergeUserDebugAssets :app:mergeLabDebugAssets --console=plain` 通过；两版合并目录中的全部样本与网页文件（含 build-info）逐字节等于源文件。
- 无改动再次 `bundleWeb` 为 `UP-TO-DATE`；删除生成 JSON 后再次构建会自动重建，两个资源合并任务成功。
- 修改的 Python/JavaScript 文件通过语法检查，`git diff --check` 通过，更新文档的本地链接有效。
- 本次未完整编译 APK、运行真实模型或进行真机验收；变化集中于目录、网页构建、样本生成与说明。未提交或发布。

汇总见 [checks.json](../../../verification/structure-cleanup/checks.json)。
