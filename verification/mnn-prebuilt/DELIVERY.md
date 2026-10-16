# 内附 MNN 后的下载构建验证

日期：2026-10-17。用户要求从 GitHub 下载后使用 Android Studio 构建，无需另行获取或编译 MNN。此次更新代码并推送 main，不创建 APK release。

## 改动

- 仓库内附 `native/mnn-prebuilt`，普通 Git clone 与 Download ZIP 均包含四个 arm64-v8a 动态库、同版本公共头文件及许可。四库合计约 4.5 MB，无 Git LFS、子模块或构建时 MNN 下载。
- 固定原提交 `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`、NDK 28.1.13356709、API 26、c++_shared、Release/-O3/NDEBUG、原分库方式。工件来自已验证的 Android 原生构建，清单记录每个编译单元的来源路径、优化和宏、SHA/build ID/动态依赖。
- CMake 默认导入预编译库，只编译 docprase 与 Android 宿主；明确链接宿主自身的 Android 日志依赖。Gradle 显式打包四库与许可证；首次构建自动 `npm ci` 准备界面依赖。
- 预编译文件构建配置时校验 SHA，并触发变更后的重配置；`.gitattributes` 保持原始字节，防止 Windows autocrlf 改写头文件和许可。上游头文件和许可中的原有空白保留。
- `setup_native_deps.py` 默认只校验内附包，发布脚本也使用检查模式。需要维护引擎时显式 `--source` 获取源码、`-PmnnSourceRoot=...` 编译；不再自动读取相邻 MNN。

## 验证

1. 从待提交 Git 源码树生成 tar 归档，并解压至 `/tmp` 独立目录；该目录没有 `.git`、`native/MNN`、相邻 MNN、旧 `.cxx` 或 npm 依赖。仅配置本机 SDK 路径。
2. 运行两版 assemble、JVM 测试和 lint：106 项 Gradle 任务全部执行，构建成功；两版各 84 项 JVM 测试均无失败。npm 依赖由 Gradle 自动安装。原生编译记录无 MNN 源码单元。见 `clean-build.txt`、`clean-build.json`。
3. 两版 APK 检查通过：arm64、公共 ABI/JNI/LLM、依赖闭合、当前 NDK libc++、四个 MNN 库与内附工件字节一致、真实后端已编译、原生 Release、裁剪资源与许可、未打包模型权重。见 `clean-apk.json`、`clean-checks.txt`。
4. 发布工具 12 项回归测试通过；新增断言默认流程仅检查内附 MNN、不执行源码获取。使用模拟上传，不创建实际 release。见 `release-tests.txt`。
5. `core.autocrlf=true` 检出后全部预编译文件仍通过校验；篡改头文件后校验器拒绝。见 `windows-checkout.json`。这不是 Windows Android Studio GUI 实测。

为避免磁盘不足产生的残缺缓存，清理了本项目可重新生成的旧 app/build 与旧 MNN 原生对象缓存，再以全新 Gradle 单次进程完成验证。源码及模型文件保留。

## 使用

下载 GitHub main 的 ZIP，解压后用 Android Studio 打开根目录；安装 JDK 17、Node.js 22/npm、SDK 35、NDK 28.1.13356709、CMake 3.31.6，选择 userDebug 或 labDebug 构建。不需要运行 MNN 源码下载脚本。

仍需正常获取 Android/Gradle/Maven/npm 依赖；首次 App 识别前仍需下载 OCR 模型。此轮未改变推理算法，未做手机推理或性能验收。
