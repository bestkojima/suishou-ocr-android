# Android 图片解码失败修复 · 0.7.1-ocr

日期：2026-10-07（Asia/Shanghai）。问题：[本地任务](../../.scratch/android-image-decode/issues/01-alpha-png-input.md)。用户截图实际错误为 `engine_ready:true` 与 `5 / decode / input_error / invalid input`；因此本次追查图片输入路径。

## 复现与原因

通过实际 `NativeOcr.java` 与 `dococr_jni.cpp`，在 Linux JVM 加载同源 C ABI 的受控识别后端。RGB 与 RGBA 两张 2×2 PNG 具有相同可见像素：RGB 返回 0，RGBA 返回 5、终态 failed、decode/input_error，与截图一致。[最小复现](repro-red.log)、[修复前回归](regression-red.log)。

`ImageInput.normalize` 生成 ARGB_8888 Bitmap 并保存 PNG；原 JNI 直接以 `DOCOCR_IMAGE_PNG` 提交。docprase `src/core.cpp` 的编码解码校验拒绝 2／4 通道 PNG。此前桌面验证主要使用 RGB PNG，JVM 图像测试覆盖 EXIF 像素和原子保存，浏览器宿主模拟也不执行此解码路径，因此未捕获这个 Android 输入适配缺陷。

排名与对照：通道不兼容优先，其次是文件格式声明不一致、文件损坏／大小限制。复现图片同为有效、小尺寸 PNG，唯一差异是 Alpha 通道，排除了模型加载和尺寸作为该复现的原因。尚未取得用户设备保存的 `source.png`，原设备上的最终因果确认以覆盖更新后重试为准。

## 修复

JNI 在原来的规范 PNG 输入处识别带 Alpha 的 8 位 PNG，规范为白纸背景的 RGB8 像素，再通过公共 C ABI 提交宽高及 row_stride。Opaque 内容保持原像素；透明与半透明区域按白纸合成。原 RGB／灰度 PNG 路径保持原行为；损坏、超限和 16 位图片继续由原契约拒收。

新增解码器使用相邻引擎已有 stb 头文件，符号限定为 JNI 内部 static，避免与引擎解码符号相互覆盖。保留 64 MiB 编码大小、6400 万像素的边界。未修改相邻 docprase／MNN 源码。

修复作用于每次读取已保存图片的入口，因此旧记录的 `source.png` 也可直接重试，不需要重新导入。版本更新为 `versionCode=8`、`versionName=0.7.1-ocr`，两版包名保持不变。

## 验证与产物

- [JNI 回归结果](result.json)、[日志](regression-green.log)：RGB、Android 同型 RGBA、半透明 RGBA、灰度 Alpha 全部成功；实际 DocumentIR 和导出资源与固定白纸 RGB 对照逐字节一致。调用完整公开 JNI，不断言私有辅助方法；损坏输入仍返回输入错误，随后同一引擎可重试。
- [生产模型结果](production/result.json)、[日志](production.log)：将此前真实验证图转成 RGBA PNG，经修复后的实际 JNI → 生产 C ABI → 九个真实模型，本次重新执行，返回 0，DocumentIR 状态 `ok`、10 个内容块，含中文、公式、表格及插图。实际 [输入](production/source.png)、[配置](production/config.json)、[DocumentIR](production/output/document.json)、[Markdown](production/output/document.md)、[资源](production/output/assets)、[run-manifest](production/output/run-manifest.json) 已保留。
- [time -v](production-time.log) 记录桌面进程耗时与峰值 RSS，包含 JVM、加载及整个作业，不作为设备性能数据。
- [Gradle](gradle.log)：两版 APK 与 Java 编译成功，完整 JVM 套件各 44 项、失败／错误／跳过均为 0；[汇总](jvm.json)。浏览器代码未修改，此修复不通过宿主模拟来宣称原生输入验证成功。
- [APK 静态检查](apk.json)、[日志](apk.log)：arm64-v8a、七个必需库、生产后端编译、C ABI/JNI/LLM 符号、依赖闭合、未打包权重均通过。

新版 APK：[普通版](../../app/build/outputs/apk/user/debug/app-user-debug.apk)、[测试版](../../app/build/outputs/apk/lab/debug/app-lab-debug.apk)。请覆盖安装与当前 App 相同的版本类型，保留应用数据和已下载模型，然后在原记录点击“重试识别”；不要先卸载应用。

## 复现命令与设备待验收

```bash
python3 tools/verify_android_image_input.py
python3 tools/verify_android_image_input.py --production --output verification/android-image-decode/production
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew testUserDebugUnitTest testLabDebugUnitTest assembleUserDebug assembleLabDebug
python3 tools/verify_ocr_apk.py --output verification/android-image-decode/apk.json
```

JNI 验证脚本需要 JDK、g++、Pillow 和相邻 `docprase/build/linux-current` 的 C ABI 库。受控后端仅用于快速通道和像素回归；生产模式明确单独运行真实模型，不把前者报告为模型推理。

当前执行环境没有连接 Android 设备。两版编译、实际 JNI 桌面测试和生产模型识别已通过，仍需用户设备覆盖安装后重试原图，确认该设备解码、后续推理、取消／重试及内存表现。
