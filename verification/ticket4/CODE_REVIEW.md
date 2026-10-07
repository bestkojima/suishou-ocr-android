# ticket4 双路代码审查

基线：`381b890d61f3ccac98177eb45fc26ecee96a9e53`。首次审查目标：`ec866dd`。按照 code-review 技能由 Standards 与 Spec 两个只读子代理分别审查，生产文件、公开桥接专项、既有 JVM 测试及证据文档均纳入。

## Standards

明确文档规范违反 0 项。改动使用简体中文、沿用本地任务与既有领域术语；交付记录区分 JVM 固定状态、AndroidHost 模拟及桌面真实 C ABI，设备验收仍待办，符合 ADR 和规格 Testing Decisions。新增测试未增加生产内部测试接口。

初次意见 3 项：

- P3，判断性 Duplicated Code：`web/app.jsx` 三次重复导航次数与当前文档 ID 的归属谓词。已提取 `isCurrentDocumentOpening`，启动回复、启动错误和取消错误使用同一判断。
- P3，正确性：占用用多文档 `Set`，按钮却只用单个 `startingDoc`；A、B 均等待回复而 B 失败后，重开 A 的按钮启用、点击却被占用保护忽略。已把界面状态改为全部待回复文档的集合，并添加公开桥接失败用例。
- P3，文档证据：首次提交中的 CODE_REVIEW.md 链接尚无目标。已补齐本报告。

额外复现限于允许迟到回复的 AndroidHost 模拟，未证明该请求交错已经在当前 Android 宿主出现。

## Spec

初次行为缺口 2 项，需求外扩张 0 项：

- P2，同页迟到启动回复覆盖较新进度。ticket4 要求“迟到、重复以及切换文档后的事件按作业归属处理，不污染其他文档或当前页面”。仅判断导航次数与文档 ID 后打开旧快照，能把较新 recognizing／cancelling 状态回退为 preparing。已在 React 状态更新回调中比较同文档、同 jobId 的 sequence，保留较新识别状态；专项同时覆盖取消事件和旧回复在同一 JavaScript 调用中到达。
- P3，多文档启动待回复的按钮状态与实际保护不一致。规格要求“作业具有稳定标识、关联文档及明确状态”，ticket4 要求重开文档恢复当前状态。问题及修复与 Standards 轴独立报告的一致，已用公开桥接交错用例验证。

缺口均由公开 AndroidHost 浏览器模拟复现。交付记录正确分开浏览器模拟、JVM 和桌面真实引擎证据；设备内加载、导航、取消清理、进程终止后重试及资源表现仍待实际 arm64-v8a 设备。

## 修复与复审

两项行为缺口均有修复前失败证据：[进度回退](review-progress-red.log)、[多文档按钮状态](review-pending-red.log)。修复后专项 8 组通过：[review-green.log](review-green.log)。完整回归以交付记录和 `*-final.log` 为准。

双路修复复审结果将在完成后追加。
