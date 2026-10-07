# ticket4 导航、取消和失败重试交付

日期：2026-10-07（Asia/Shanghai）。任务：[04：贯通导航、取消和失败重试](../../.scratch/android-real-ocr/issues/04-navigation-cancel-retry.md)。本轮基线：`381b890d61f3ccac98177eb45fc26ecee96a9e53`，代码提交为 `ec866dd`、`76bb524`，当前分支为 `main`。

## 实施结果

既有应用级控制器已管理单作业、协作取消、资源清理、输入保留、进程中断恢复与模型修改保护。本轮补齐异步启动和取消回复的导航归属：

- 返回首页或切换文档之后，迟到的启动回复不会打开旧文档；原作业仍可从历史记录重开。
- 重开同一文档之后，旧启动回复不能覆盖刚恢复的新进度。导航次数与文档 ID 共同用于判断回复是否仍属于当前页面。
- 旧文档启动／取消失败的回复不会在新页面显示错误；当前页面的失败仍正常提示。同页旧启动快照按 jobId 和 sequence 比较，并在 React 状态提交时保留较新的进度及取消中状态。
- 启动等待期间按文档维护全部待回复请求，重开文档仍禁用重复提交，失败回复后恢复重试按钮。
- 扩充既有 JVM 控制器检查：运行及取消中拒绝第二项、加载、模型删除和当前输入删除，已提交结果不再取消，过期 jobId 被拒绝；模型资源不可用时可用新 jobId 重试，安全删除后就绪状态失效。

导航切换、重复提交、启动错误串页和取消错误串页均有修复前失败日志：`navigation-red.log`、`start-red.log`、`error-red.log`、`cancel-red.log`。审查补充用例的修复前失败日志为 `review-progress-red.log`、`review-pending-red.log`；最终结果见完整回归记录。

## 验证结果

| 检查 | 结果与证据 |
| --- | --- |
| 普通／测试版 JVM 完整套件、Java 编译与 APK 构建 | 各 44 项，0 失败／错误／跳过；[Gradle 日志](gradle-final.log)、[XML](jvm) |
| 普通／测试版 Android lint | 各 0 错误、11 警告；[普通版 XML](lint-user.xml)、[测试版 XML](lint-lab.xml) |
| 既有浏览器回归、模型管理、排序、拖动校对 | 19 + 6 + 7 + 12 项；日志 `web-final.log`、`models-final.log`、`reorder-final.log`、`editor-final.log` |
| 原生请求／事件桥场景 | 10 组；[日志](recognition-final.log)，AndroidHost 为浏览器模拟 |
| 导航与迟到请求回复专项 | 8 组；[日志](navigation-final.log)、[报告](navigation.json)，AndroidHost 为浏览器模拟 |
| 实际结构化输出展示与往返保存 | 8 组；[日志](real-results-final.log)、[报告](real-results.json)，复用既有真实输出 |
| APK 架构、符号、依赖、生产后端和无权重打包 | 通过；[日志](apk.log)、[APK 身份](apk.json) |
| 桌面生产 C ABI 的取消与重复作业 | 8 项检查通过；[日志](desktop.log)、[报告](desktop/lifecycle.json) |
| 规范／规格双路审查和修复复审 | 各 0 项剩余发现；[审查报告](CODE_REVIEW.md) |

全部浏览器检查共 70 项。汇总见 [results.json](results.json)。项目为 Java／JSX，没有独立 TypeScript 类型检查；本轮通过 Java 编译、esbuild 打包、脚本语法检查及 Android lint。双路审查见 [CODE_REVIEW.md](CODE_REVIEW.md)。

APK：[普通版](../../app/build/outputs/apk/user/debug/app-user-debug.apk)、[测试版](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)，均为 arm64-v8a Debug 包。

## 真实引擎证据与边界

本轮重新运行 Linux x86_64 生产 C ABI：实际加载、推理中第二项及销毁返回 BUSY、协作取消至 terminal、取消无有效正文、同引擎取消后再次识别、重复输入输出和真实空白页均通过。完整作业输出、配置、原图及资源保存在 [desktop](desktop)，内容身份见 [desktop-identities.json](desktop-identities.json)，运行元数据见 [run-manifest.json](desktop/repeated/run-manifest.json)。通过 `--output` 单独保存本轮证据。

加载约 10.63 秒，本次完整生命周期脚本约 56.12 秒，进程峰值 RSS 2,431,896 KiB；这些是桌面数据，不代表 Android 性能。重复输入使用既有 `odb-09` 原图，重新产生 [DocumentIR](desktop/repeated/document.json) 和 [Markdown](desktop/repeated/document.md)。浏览器结构化结果回归复用此前输出，本轮没有把新的桌面结果冒充设备输出。

JVM 清理期检查沿用既有固定控制器状态，不执行同步 JNI 推理或真实清理；失败后重试明确使用不可用模型资源场景。浏览器通过公开 AndroidHost 请求／事件和用户操作验证契约。真实 C ABI 清理及重复调用的证据单独来自桌面引擎，三种环境分别记录。

## 设备待验收

[adb devices](devices.log) 无连接设备。需在支持的 arm64-v8a 设备补齐：

- APK 内实际模型加载，拍照及导入图片的首次／重复离线识别。
- 识别中返回首页、重开文档、切后台或重建 Activity；作业继续且状态恢复正确。
- 显式取消立即进入取消中，当前区域、安全恢复及资源清理结束后才进入终态；期间第二项、模型和输入删除均受到保护。
- 取消／失败后保留输入并重试；无有效原生结果时不显示伪造正文。
- 系统杀进程之后输入和已保存结果仍可重开，遗留活跃状态转为可重试终态。
- 安全删除模型后，下一项重新验证就绪状态；记录设备内存、耗时和资源释放。

本轮构建、JVM、浏览器与桌面结果不表示上述设备验收通过。
