# ticket5 APK、真实识别预览与集成交付

日期：2026-10-07（Asia/Shanghai）。任务：[05：交付 APK、真实识别预览及分项验收记录](../../.scratch/android-real-ocr/issues/05-integrated-delivery-evidence.md)。本轮基线：`6ed99b4`，提交到当前 `main` 分支。本轮重新执行验证，保留此前 ticket1～4 和 real-ocr 的证据。

## APK 与 Android 编译

- [普通版 APK](../../app/build/outputs/apk/user/debug/app-user-debug.apk)：`cn.local.ocr`。
- [测试版 APK](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)：`cn.local.ocr.test`。
- 两版均为 `0.7.0-ocr`、arm64-v8a、Debug 签名。构建及 Java 编译通过：[构建日志](apk-build.log)、[最终 Gradle 日志](gradle-final.log)。增量构建复用已编译且未变更的原生目标。
- [静态检查](apk.json) 记录 APK 大小与 SHA-256，核对七个必需原生库、AArch64、DT_NEEDED 依赖闭合、C ABI/JNI/LLM 符号、`DOCOCR_HAS_MNN=1` / `DOCOCR_HAS_LLM=1` 生产后端编译命令，并确认未打包权重。
- 默认分库构建通过；合并库模式未单独验收。编译和静态检查不代表设备加载或推理成功。

## 本轮桌面真实识别

Linux x86_64，调用同源 docprase/MNN 生产 C ABI；不调用服务器，也不修改相邻项目源码。实际源码、工作区状态和桌面二进制身份：[source-identity.json](source-identity.json)。九个本地模型的大小和固定 SHA 全部校验通过：[models-local.json](models-local.json)。本轮未重新查询远端仓库 revision。

本次重新识别已有验证图片，重新产生结果；未使用历史输出代替执行。输入：[source.png](source.png)，1000×1380，来源是 `verification/real-ocr/source.png`。实际配置：[lifecycle-config.json](lifecycle-config.json)，与 App 的 `ocr/config.json` 除模型根目录外一致，CPU 单线程、512 token 预算。

- [DocumentIR](output/document.json)、[Markdown](output/document.md)、[run-manifest](output/run-manifest.json)、[资源](output/assets)、[可导入 ZIP](output.zip) 均来自本轮生产引擎。实际状态 `ok`，10 个内容块，含中文、LaTeX、HTML 表格和插图。
- [生命周期报告](lifecycle.json) 记录实际加载、运行中第二项及销毁返回 BUSY、协作取消到 terminal、取消无有效正文、同引擎恢复后完整识别、重复输入识别及空白页，共 8 项通过。
- 重复输入：[partial-source.png](partial-source.png)，从既有 odb-09 原图规范得到；本次 [重复输出](repeated/document.json) 实际状态 `ok`。纯白输入：[blank.png](blank.png)，[实际输出](blank/document.json) 原生状态 `partial`，无内容块且有 `empty_page` 证据，App 明确展示为空白页。
- [日志](desktop.log) 与 [time -v](desktop-time.log) 记录退出码 0、加载约 5.71 秒、完整生命周期执行约 68.73 秒、峰值 RSS 2,470,644 KiB。这包括多个作业，不是单张图片耗时，也不代表 Android 性能；单次阶段耗时见 run-manifest。

## 界面预览与桥接模拟

[真实结果浏览器报告](browser.json) 记录实际导入 ZIP 的绝对路径与 SHA-256，指向本轮 ticket5 输出。可查看：[文字与公式](preview-text.png)、[表格与插图](preview-structured.png)、[partial 提示](preview-partial.png)、[手机布局](preview-mobile.png)。Chromium 导入真实结果后回放，不执行模型。

partial 预览使用[既有真实 partial 样本](partial/document.json)，[provenance](partial/provenance.json) 明确记录历史来源；它不替代本次真实推理。手机布局截图同样使用该历史 partial 样本。合法资源路径变体是基于真实内容的测试改写，不是新的推理输出。

[公开请求／事件桥测试](recognition.json) 增加连续场景：缺模型输入与历史重开 → 浏览两个仓库、提交九个工件下载 → 文件完成仍需加载 → 加载就绪 → 启动 → 结果保存展示 → 校对 → 重新识别另存 → 重开旧记录并发出校对后 Markdown 导出请求。该场景的下载、加载、原生作业、正文和导出宿主均明确为 AndroidHost 模拟；实际 ZIP/MD 内容另由真实输出的浏览器往返检查及 JVM 检查验证。

既有场景继续覆盖返回首页、冲突、取消中与终态、取消后重试、失败与进程中断后重试、旧事件隔离、回放暂停及模型保护；[导航专项](navigation.json) 覆盖迟到启动／取消回复。这些结果不证明 APK 内 JNI 的实际生命周期。

## 回归与证据

| 检查 | 本轮结果与边界 |
| --- | --- |
| 普通／测试版 JVM 完整套件 | 各 44 项，失败／错误／跳过均为 0；[XML](jvm)、[日志](gradle-final.log)。包括 PDF、Office、JSON/ZIP、下载、EXIF、排序、校对及控制器既有检查；控制器固定状态检查不执行 JNI |
| Java 编译、esbuild、JS/Python 语法 | 通过；[Java 日志](compile.log)、[前端打包](web-build.log)。项目无独立 TypeScript 类型检查 |
| Android lint | 两版各 0 错误、11 个既有警告；[普通版](lint-user.xml)、[测试版](lint-lab.xml) |
| 浏览器完整回归 | 19 项；[报告](web.json)、[日志](web.log) |
| 模型、排序、拖动校对 | 6 + 7 + 12 项；[模型](models.json)、[排序](reorder.json)、[编辑](editor.json) |
| 识别桥接与导航 | 11 + 8 组；[桥接日志](recognition-final.log)、[导航日志](navigation.log)，原生宿主模拟 |
| 本轮真实输出展示与保存导出往返 | 8 组、无页面异常；[报告](browser.json)、[日志](real-results.log) |
| 原生 ABI 既有回归 | Linux 受控测试后端，4 项通过；[日志](abi-regression.log)，独立于上述生产模型推理 |

浏览器合计 71 项，汇总：[results.json](results.json)。[artifacts.json](artifacts.json) 记录本轮输入、输出、ZIP、预览和报告的大小与 SHA。

预览脚本原先忽略指定输入目录，在缺失目录下仍成功读取旧输出；[修复前日志](preview-source-red.log) 与[修复后负向检查](preview-source-green.log) 记录该问题和修复。现在通过 `OCR_RESULT_DIR` 选择实际输入，报告同时记录来源身份。拖动校对首次与桥接脚本共用 4196 端口，连接中断；[失败日志](editor-port-conflict.log) 保留，单独重跑 [editor.log](editor.log) 全部通过。新增组合场景的两次宿主测试设置问题已修正，完整桥接回归通过。

规范和规格审查结果见 [CODE_REVIEW.md](CODE_REVIEW.md)。

## 复现

从项目根目录执行；选择新的空证据目录以保留之前结果，partial 来源样本需显式复制并保留 provenance：

```bash
cmake --build ../docprase/build/linux-current --target dococr_cli dococr_c -j2
python3 tools/verify_real_ocr.py --output verification/ticket5-new --input verification/real-ocr/source.png
cp -r verification/real-ocr/partial verification/ticket5-new/partial
python3 tools/package_real_preview.py --output verification/ticket5-new
OCR_RESULT_DIR=verification/ticket5-new OCR_EVIDENCE_DIR=verification/ticket5-new node web/verify-real-results.mjs
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleUserDebug assembleLabDebug
python3 tools/verify_ocr_apk.py --output verification/ticket5-new/apk.json
```

## 设备待验收与实现边界

[adb devices](devices.log) 无连接设备。以下尚未执行，不能标记为通过：

- [ ] arm64 Android 安装两版 APK，App 内下载／校验九个工件，实际加载 JNI、全部运行库和模型。
- [ ] 拍照与导入单图首次／重复离线推理，核对原图、DocumentIR、Markdown 和资源；准备后断网识别。
- [ ] 非对称 EXIF 1～8 的解码、旋转与镜像，相机方向及结果几何。
- [ ] 测量模型加载与推理耗时、峰值内存、清理后资源释放及后台限制。
- [ ] 导航继续、重开文档、第二项冲突、安全取消、清理期间重试限制、失败后重试和活跃模型保护。
- [ ] 系统杀进程后输入／已保存结果保留，遗留作业可重试；不承诺推理续跑。
- [ ] 成功／partial／空白／失败显示，校对、排序、TXT/MD/ZIP 系统分享与再导入；重新识别保留旧校对。

实现范围保持单图、本地离线、arm64-v8a。PDF/Office 延续原生提取与结果回放；未扩展其 OCR、token 流、服务器运行时后端或应用商店签名。开发说明：[REAL_OCR.md](../../docs/REAL_OCR.md)。设备缺失不阻塞已约定的替代交付，Android 编译、桌面真实推理、桥接模拟和浏览器渲染仍分项报告。
