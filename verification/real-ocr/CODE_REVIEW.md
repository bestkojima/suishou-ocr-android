# 单图 OCR 实施审查

日期：2026-10-07。基线 `a981624`，实施提交 `b153fcc`，复审覆盖其后的当前工作区修正。依据根目录 AGENTS.md、CONTEXT.md、docs/agents/ 和 ADR，以及 `.scratch/android-real-ocr/spec.md` 和五项任务。按 code-review 技能分两路独立审查；复审为只读检查，执行测试由主实施流程完成。

## Standards

规范轴复审通过：硬性违反 0，判断性 smell 0，新增实质正确性问题 0。

原 P2 已解决：ImageInput 使用同目录临时文件、同步及原子替换；写入失败不会留下损坏 source.png，也不会覆盖既有完整文件。新增测试覆盖失败后的重试、原输入保留及再次失败。

已保存结果的取消保护、进程重开状态恢复和 ZIP 资源去重改动均符合既有架构及最少修改要求。已查阅 JVM、控制器与 UI 回归通过记录；本轮只读复审未重跑测试。

控制器补充测试仅固定内部状态作为输入，不断言私有字段；断言公开取消／查询响应与持久化终态。它不代替 AndroidHost 契约检查和设备生命周期验证。

## Spec

原 3 项 P2 均已解决：

- PNG 改为临时文件写入、同步后原子替换，失败清理临时文件；不会留下阻止重试的规范图片。
- 结果提交后以 resultSaved 阻止取消，界面禁用取消按钮；进程重启能恢复已保存结果的成功／partial／空白终态。
- ZIP 同时保存全部声明资源及原生 assets 工件，并去重原图，合法的 images 路径不再丢失。

已检查新增回归测试及通过日志：写入失败后重试、非 assets 资源导出、提交前后取消、重启恢复和界面结束状态均有对应覆盖。此次复审未重新运行测试。

未发现新的实际阻塞。控制器 JVM 测试固定私有状态验证公开方法分支，不证明真实 native 清理时序；现有设备待验收边界仍应保留。

规范轴：遗留 0 项；规格轴：遗留 0 项。两轴原发现均已修复并复审通过。

## 回归记录

- [ZIP 回归先失败](review-red.log)、[PNG／ZIP 修正后通过](review-green.log)。
- [提交后取消界面先失败](review-ui-red.log)、[修正后通过](review-ui-green.log)。
- [最终完整 JVM 回归](review-final-jvm.log)：两种构建各 43 项，0 失败／异常。
- [两款 APK 构建](review-final-gradle.log)、[原生打包检查](apk.log)、[APK 身份](apk.json)。
- [既有浏览器](review-final-browser.log)、[模型页](review-final-models.log)、[排序](review-final-reorder.log)、[编辑拖动](review-final-editor.log)、[真实输出展示](review-final-real-results.log)。

设备验收尚未执行，见 [交付记录](DELIVERY.md)。
