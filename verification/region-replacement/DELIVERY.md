# 区域重新识别自动替换

日期：2026-10-13。当前修改与本地 APK 已完成，尚未发布新的 release。

## 问题和修复

用户要求重新识别后清除旧模型错误输出。v0.7.9 的实现仅把新结果持久化为候选，仍需手动采用并保存；主界面还忽略了区域作业事件。因此完成识别后，阅读正文、已有校对和导出继续使用旧内容。

本轮按最新要求取消手动采用步骤：可用结果生成并完成作业清理后，将区域正文、校对值、结果状态和作业状态一起写入文档，再通知界面刷新。原始 DocumentIR 文件保留作核对依据，不作为当前阅读内容使用。失败、取消、空白/无可用输出不应用；部分结果有文字时替换并保留核对提示。

主界面处理区域完成事件并定期同步，校对面板关闭后仍能刷新对应正文。刷新绑定文档、区域及作业，已应用的作业不会重复覆盖人工修改。校对保存改为单区域补丁，附区域版本检查，避免旧草稿或其他区域保存把新结果覆盖回去；导出不再提交整个旧 edits 快照。

## 验证

- 修复前运行 `./gradlew testUserDebugUnitTest --tests cn.local.ocr.EngineReuseTest.regionResultReplacesOldOutputWithoutChangingDefaultModelOrOtherBlocks`，断言识别完成后正文被替换，得到 1 项失败；见 `reproduction.txt`。
- 修复后双变体各 84 项 JVM 测试无失败：自动应用、同区域旧草稿拒绝、其他区域保存隔离、完成后人工修改、原始文件保留、无内容/失败/取消不覆盖。
- `npm run test:web`、`node web/verify-live-recognition.mjs` 通过。模拟 Android 桥验证正文和草稿自动替换、关闭后完成、迟到回复隔离、历史重开、重复完成通知不覆盖人工修改；原有发票表格、手工校对、回放和导出回归通过。
- 两版 lint、assemble 通过；APK 原生 Release、ABI、依赖、无权重打包检查通过。见 `gradle.txt`、`web-tests.txt`、`ui.json`、`apk.json`、`build.json`。
- 此轮未改变原生模型推理，不重复运行模型对照。WebView/JNI 手机全链路仍待验收，浏览器模拟桥不代替真机测试。

本地 APK 沿用 0.7.9-ocr／16；它们是本轮修复后的构建，GitHub 已发布的 v0.7.9 资产保持不变。
