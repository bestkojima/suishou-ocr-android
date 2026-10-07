# 模型下载校验与加载流程调整

日期：2026-10-07（Asia/Shanghai）。工作基线：`460781f`，包含已合入的 Android Alpha PNG 解码修复。用户补充要求：只在下载模型时完成内容校验，加载过程参照 MNN Chat。

## 实现

- 下载服务保留文件大小与 SHA 校验；校验成功、最终文件保存后记录 `verifiedSize`、`verifiedModified`、`verifiedSha256`。加载读取这些记录和文件元数据，拒绝缺文件、不完整下载、仅大小校验、工件不匹配和新记录中已变化文件。旧版匹配固定工件的 `verified` 下载记录继续可用。
- Android 构建生成 config／版面／识别三份适配源码，移除加载时重复文件 SHA 扫描。相邻 docprase 与 MNN 的源码没有修改；CMake 对替换位置做精确匹配，源引擎变化或仍有文件 SHA 调用时构建失败。该策略仅用于本 Android 下载校验边界；原始桌面后端仍保留其 SHA 校验。
- 参考本地 MNN Chat 的 `apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp` 中 `LlmSession::Load`，按 `createLLM → set_config → load` 初始化。启用 mmap 并提供私有可写缓存目录，按模型／配置身份隔离，记录 Ovis 初始化和总加载耗时。CPU 单线程及既有取消、清理、重新识别行为保持原实现。
- 入口改为“加载识别模型”。下载完成、文件就绪和实际加载成功仍分别呈现。需要重新校验时使用下载页“下载 / 继续所选文件”，完整本地文件可校验复用。

## 验证

| 检查 | 结果与执行边界 |
| --- | --- |
| 普通／测试版 JVM | 各 49 项，0 失败／错误／跳过；[结果汇总](results.json)、[XML](jvm)、[Gradle 日志](gradle-final.log) |
| 下载校验与加载记录回归 | 覆盖下载边界正确／错误 SHA、大小不符、仅大小校验；九个必需工件、缺失记录、元数据变化、旧记录兼容及加载不再计算内容 SHA。新增接口实现前的 [失败日志](readiness-red.log) 为编译缺失，行为验证以最终 JVM 结果为准 |
| 模型页／识别桥接／导航 | 6 + 11 + 8 组通过；[模型](models.json)、[识别](recognition.json)、[导航](navigation.json)。Chromium 和 AndroidHost 模拟，不执行 APK |
| 两版 Android lint | 各 0 错误、11 个既有警告；[普通版](lint-user.xml)、[测试版](lint-lab.xml) |
| 两版 APK 构建和静态检查 | `0.7.1-ocr`、arm64-v8a，保留 PNG 修复；[APK 报告](apk.json) 验证库、符号、依赖和生产后端，未打包权重 |
| 原生加载路径检查 | [报告](native-loading.json)：三个实际 Android 编译对象均不引用文件 SHA 函数，生成源码也无该调用 |
| 同源加载与真实推理 | 使用同一 Android 原生源码适配层编译的 Linux x86_64 Release 库，实际模型先在测试准备阶段校验；随后首次及 mmap 缓存复用加载并对样图真实识别，两次均 `ok`、10 个内容块。非 Android 执行 |

桌面模型实际加载约 **3.010 秒／0.844 秒**，分别为首次加载和复用同目录 mmap 缓存后的重新创建。Ovis 单独初始化约 1856 ms／128 ms。这组数据不是设备耗时，也不是对既有报告的严格性能对比。输入图片 SHA、库 SHA 与实测值见 [加载报告](host/loading.json)，实际结果见 [第一次 DocumentIR](host/run-1.json)、[第二次 DocumentIR](host/run-2.json)、[推理日志](host-smoke.log)。该烟测仅保存 JSON／Markdown，不验证完整资源导出。

宿主构建与运行可复现：

```bash
# 将 host-build-config.cmake 放入新的临时目录并命名为 CMakeLists.txt；其中路径为本机源码路径。
cmake -S /tmp/android-ocr-loading-host -B /tmp/android-ocr-loading-host/build -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/android-ocr-loading-host/build --target dococr_c -j2
python3 tools/verify_model_loading.py --library /tmp/android-ocr-loading-host/build/docprase/libdococr_c.so --output verification/model-loading-new
```

构建配置：[host-build-config.cmake](host-build-config.cmake)，[构建日志](host-build.log)。测试 mmap 缓存位于独立临时目录，结束后清理；没有把运行缓存或模型权重写入仓库。

## APK 与设备验收

- [普通版 APK](../../app/build/outputs/apk/user/debug/app-user-debug.apk)：`cn.local.ocr`。
- [测试版 APK](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)：`cn.local.ocr.test`。
- 可覆盖安装同类型开发签名 APK，保留已有模型和下载记录；实际安装尚未执行。
- 当前虚拟机 `adb devices` 为空。仍需实机验证首次／缓存复用加载耗时、额外缓存磁盘占用、内存、真实识别及取消／清理；mmap 首次建缓存可能包含额外磁盘写入，设备改善幅度以日志实测为准。

设备连接后，可读取：

```bash
adb logcat -v time OcrEngine:I '*:S'
```

本次工作区修改尚未提交。原阶段收尾记录及此前交付证据保留；此次新增要求和验证在本目录独立记录。
