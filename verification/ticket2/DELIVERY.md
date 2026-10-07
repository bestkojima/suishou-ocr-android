# ticket2 补充实施与验收

日期：2026-10-07（Asia/Shanghai）。任务：[02：单张图片真实识别和结果展示](../../.scratch/android-real-ocr/issues/02-single-image-real-recognition.md)。实施前基线：`ee254ce76e19781f69e2fb53b1c75990f3e0ee96`；代码提交：`6ef5a36`、`5193636`，均在当前 `main` 分支。

## 实施结果

基线已包含输入保存、EXIF 规范、后台 docprase 生产推理、DocumentIR／Markdown／资源落盘、历史和结果回放。本次核验后补齐三处事件边界：

- 同一作业的重复序号不能将终态改回识别中。
- React 提交状态时再次核对文档、作业及序号，连续到达的乱序进度不能覆盖较新阶段；异步读取结果返回时也重新校验。
- 原生实际阶段／区域进度变化递增序号。进程退出后的恢复终态使用高于落盘状态的序号，并保存新序号；重复查询保持稳定。

重复序号及连续乱序用例均在修复前出现 `1 !== 0` 断言失败，修复后通过。规格审查发现恢复终态的关联回归后，JVM 用例同样先失败再修复：[失败日志](recovery-red.log)、[通过日志](recovery-green.log)。最终完整套件还覆盖了已提交 partial 结果的恢复序号和重复查询稳定性。

## 验证结果

| 检查 | 结果与证据 |
| --- | --- |
| 普通／测试版 Java 编译、JVM 完整套件、APK 构建 | 各 43 项，0 失败／错误；[最终 Gradle 日志](gradle-final.log) |
| 既有浏览器回归 | 19 项通过；[日志](web.log) |
| 模型界面 | 6 项通过；[日志](models.log) |
| 顺序调整 | 7 项通过；[日志](reorder.log) |
| 长按与编辑 | 12 项通过；[日志](editor.log) |
| 识别请求／事件桥 | 10 组通过，包含重复、连续乱序、进程退出后的历史重开；[日志](recognition.log) |
| 实际输出展示与导出再导入 | 3 组通过；[日志](real-results.log) |
| APK 架构、依赖、符号、生产后端及无权重打包 | 通过；[日志](apk.log)、[大小与 SHA](apk.json) |
| 规范／规格双路代码复审 | 各 0 项剩余发现；[审查报告](CODE_REVIEW.md) |

汇总：[results.json](results.json)。项目使用 Java 和 JSX，没有独立 TypeScript 类型检查任务；通过 Gradle Java 编译及现有 esbuild 打包检查。

APK：[普通版](../../app/build/outputs/apk/user/debug/app-user-debug.apk)、[测试版](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)。两者均为 arm64-v8a Debug 包。

本次真实输出展示使用上轮桌面生产引擎产出的 [DocumentIR](../real-ocr/output/document.json)、[Markdown](../real-ocr/output/document.md) 和资源；本次没有重新执行模型。上轮新文字图片真实推理、耗时／内存及可查看预览见 [原交付记录](../real-ocr/DELIVERY.md)。AndroidHost 场景为浏览器模拟，JVM 序号检查不代表实际 Android 进程退出或 JNI 时序已验证。

## 设备待验收

本次 `adb devices` 仍无连接设备。APK 内模型实际加载、新拍照及导入图片推理、EXIF 解码、运行内存／耗时和生命周期操作仍待设备验收，沿用 [既有设备清单](../real-ocr/DELIVERY.md#设备待验收)。本次构建与模拟检查不表示设备推理通过。
