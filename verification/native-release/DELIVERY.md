# 0.7.5 原生 Release 优化与关闭 mmap：交付记录

核对日期：2026-10-08（Asia/Shanghai）。版本 0.7.5-ocr / versionCode 12；两类 APK 仍为开发签名 Debug 变体，原生代码为 Release。预发布，不是用户尚未宣布的 1.0.0 正式版。

## 修改与构建证据

- Gradle 设置 CMAKE_BUILD_TYPE=Release、cppFlags=-O3，CMake 在 project() 前固定 Release。MNN／docprase／JNI 的 C/C++ 编译实际最终 -O3、NDEBUG，MNN 不含 DEBUG／MNN_DEBUG。保留 MNN 分库、固定源码、应用标识及签名。
- Ovis 加载前设置 use_mmap=false、kvcache_mmap=false，语言／视觉后端共享配置；tmp_path 指向已有私有临时目录，不创建或复用 ovis-mmap-*，不删除旧缓存。不改变模型文件、下载 SHA 校验边界、prompt、CPU 设置、分辨率、区域重试及 token 上限。
- 加载后有效配置校验新增 mmap／KV 标记，manifest 保持 false，日志为 mmap=0。加载失败没有自动开启 mmap 的分支。
- APK 检查先对旧包失败，原因明确为原生 Debug（baseline-guard.log）；改后通过（apk-check.log）。用 APK 中 JNI／dococr／MNN 三个 ELF build ID 唯一关联 AGP 目录，逐一检查实际 C/C++ 命令，不混合历史缓存。两版共用同一原生 Release 构建，394 条 C/C++ 编译命令（含生产目标和辅助目标）；原始命令／库依赖／符号／包大小与 SHA 见 apk.json。
- 两版均与 0.7.4 的开发证书相同，包名 cn.local.ocr／cn.local.ocr.test、versionCode=12、versionName=0.7.5-ocr；见 checks.json、signature-*。固定 MNN 为 baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6，79 个 vendored 文件与依赖清单逐文件 SHA 一致。未修改相邻源码或模型权重。

## 回归

两版 JVM 各 62 项，0 失败／错误／跳过；两版 lint 0 错误、各 11 个既有警告。浏览器共 78 组：原有 7 个脚本 70 组，加本轮 Linux 真实结果展示／导出／往返 8 组。无页面错误或基础测试外部请求。

真实输出浏览器脚本原先使用未限定的 select；0.7.4 新增图片分辨率后出现两个下拉框。本轮修正为“JSON 回放每次字符数”标签定位，复验通过；没有为修复测试改动产品界面。browser-real 的完整输出和空白页来自本轮；partial 使用标有 provenance 的历史真实样本，未冒充新推理。

最终同源库另执行：两次加载／销毁后重新创建并真实识别、真实流式生成（run 返回及区域结束前可读）、原始流与最终保存 attempt 相符、中文／emoji 字节边界／重试／作业隔离、安全取消／BUSY／同引擎恢复／重复输入／真实空白页及资源导出。loading、live、buffer、lifecycle 目录与原始日志保留完整证据。关闭 mmap 的运行目录没有新权重缓存；加载烟测预置旧缓存哨兵，识别后 SHA 未变，oldCachePreserved=true。

## 三配置真实模型对照

Linux x86_64 VirtualBox，4 个逻辑处理器、CPU 4 线程；固定同源 MNN／docprase／Android 适配＋实际 PP-DocLayoutV3／OvisOCR2。输入为仓库 source.png（1000×1380）并校验同一 SHA；三组使用相同配置／模型工件，在计时加载前验证模型 SHA。各创建一个引擎并连续识别两次；计时期间不运行构建或浏览器检查。首次是该引擎首次推理，不是清空系统磁盘缓存后的结果。Debug 对照保持 O0 与 DEBUG／MNN_DEBUG，省略宿主 DWARF（-g0）；APK 本来也会 strip 符号。源码来自 70d5e69 的不可变快照并核对字节；host-build 是可复用配置。

单位：秒；内存为 MiB，进程生命周期峰值 RSS，不是单区域峰值。

| 配置 | 首次加载 | 首内容 首次／复用 | 整页 首次／复用 | 复用 prefill 合计 | 复用时进程峰值 RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Debug+mmap | 2.725 | 19.795 / 8.172 | 77.557 / 69.703 | 43.607 | 2539.7 |
| Release+mmap | 2.753 | 13.386 / 1.759 | 24.443 / 12.527 | 7.785 | 2380.4 |
| Release+no-mmap | 2.059 | 1.953 / 1.726 | 12.691 / 11.946 | 7.516 | 2388.3 |

Release 开／关 mmap 的四次真实内容全部一致。关闭 mmap 后不生成原先约 1 GiB 的 .static 权重缓存；复用时进程峰值内存相近，不能据此保证所有设备或密集文档的内存上限。完整 vision／prefill／decode／tokens／首内容／总时间和内存采样见 matrix/comparison.json、各 benchmark.json、timeline 与原始日志。这些数字不代表 Titan_1 性能，不能直接套用交接单的单区域阈值。

### 已发现并解释的内容差异

Debug 基线两次稳定为 partial：b0006 输出“## E = mc²”，被现有公式校验标记 invalid_formula_syntax；b0010 多输出 ## 标题标记。Release 两种 mmap 设置各两次稳定为 ok：公式为有效 LaTeX \mathrm{E}=\mathrm{mc}^{2}，图注文字不变且不多加标题。

已实际查看原始图片（E=mc² 与图注）并逐块比较，所有其他正文、完整表格结构、块类型、阅读顺序、资源引用相同；Release 内容完全匹配既有 verification/ticket5/output/document.json 的真实内容。差异与编译模式相关，未发现 mmap 开关导致内容变化。不是数值逐比特一致或通用准确率保证；没有计算人工标注 text_ED／formula_ED／TEDS。

初始严格检查失败与旧 Debug partial 原始输出均保留，没有改写模型原文。comparison-strict.json 保持 exact contentEquivalent=false；quality-review.json 仅接受这张图和这组前后内容 SHA 的已核对差异，comparison.json 记录 reviewedDifferencesAccepted=true。未来输入或输出变化不会被这份复核记录自动放行。

## 发布与设备边界

仓库：https://github.com/bestkojima/suishou-ocr-android

已发布预发布：https://github.com/bestkojima/suishou-ocr-android/releases/tag/v0.7.5

资产为 suishou-ocr-0.7.5-arm64.apk、suishou-ocr-0.7.5-lab-arm64.apk、SHA256SUMS.txt。签名兼容旧版；首次使用仍需在 App 下载模型。APK 使用最终工作区构建产物，版本标签绑定源代码；上传后已逐个重新下载，字节 SHA 与本地构建完全一致，GitHub 资产 digest 也一致；见 publication.json。

ADB 列表为空（adb-devices.txt）。尚未执行 Android 真机模型加载、相机／Bitmap、峰值内存、Titan_1 单区域／整页计时及 Windows 设备对照；交接单 r0014.png 与原始日志不在本工作区。后续按同图／模型／线程／分辨率预热一次、计时三次采集实际日志。设备待验收不阻止用户已授权的本次预发布，只有用户宣布正式版时才升级到 1.0.0。

## 已完成的 GitHub 发布校验

发布于 2026-10-08 17:34:45 CST；draft=false、prerelease=true。源代码提交 aaee2edb219af6ff237a618433e94f93111ec2a5，不可变版本标签 v0.7.5 指向该提交；发布后的校验记录另以文档提交补充到 main，版本标签不移动。

- [SHA256SUMS.txt](https://github.com/bestkojima/suishou-ocr-android/releases/download/v0.7.5/SHA256SUMS.txt)：192 字节，SHA256 `23767dbab95f947900a3c1afdba8e39f6c286ff8fdb6f86bb19f839bda957772`；下载复核一致。
- [suishou-ocr-0.7.5-arm64.apk](https://github.com/bestkojima/suishou-ocr-android/releases/download/v0.7.5/suishou-ocr-0.7.5-arm64.apk)：26,236,844 字节，SHA256 `498e1e1c718ccbe9111860eef4a1d3789abc7ea2180d223d58e637e3438767a6`；下载复核一致。
- [suishou-ocr-0.7.5-lab-arm64.apk](https://github.com/bestkojima/suishou-ocr-android/releases/download/v0.7.5/suishou-ocr-0.7.5-lab-arm64.apk)：26,236,860 字节，SHA256 `22ac6a3a6fb956f3b40b88c0acb70f51801b6920028921e0892c8b9732aa5b34`；下载复核一致。

0.7.4 原有发布及三个资产仍存在。未创建 PR、远端 Issue 或正式版 1.0.0。当前工作区代码、编译数据及发布产物已交付；Android 真机验收按上述边界保留待办。
