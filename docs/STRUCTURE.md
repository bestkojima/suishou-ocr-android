# 目录与流程维护

核对日期：2026-10-17。当前构建以根目录 `package.json`、`app/build.gradle` 为入口，识别行为以 [REAL_OCR.md](REAL_OCR.md) 为准。

## 当前目录

| 目录 | 维护职责 |
| --- | --- |
| `app/src/main/java/` | Android 采集、导入、模型、作业和存储 |
| `app/src/main/cpp/` | JNI、Android 后端及源引擎构建适配 |
| `web/` | 正式界面、宿主桥、回放状态和浏览器验证 |
| `fixtures/replay/` | 原始回放样本、图片及来源 SHA，供 App 和浏览器共用 |
| `native/docprase/` | 构建用源码快照；引擎开发先在源仓库完成，再同步文件与 SHA |
| `native/mnn-prebuilt/` | 随源码分发的固定 MNN 运行库、头文件、许可和构建证据；默认不获取或编译 MNN |
| `tools/` | 依赖准备、验证、性能对照和发布；`debug/` 保留 BUG-002 实验 |
| `verification/` | 历史交付与诊断证据；文件旧不等于可以删除 |
| `.scratch/` | 本地规格和逐项任务 |

`web/generated/`、`web/dist/`、`app/src/main/assets/web/` 和 `app/src/main/assets/fixtures/` 都由构建生成，不跟踪。版本清单和许可证仍需维护，不能当作构建缓存删除。

## 已收拢的流程

1. 移除早期 `prototypes/ocr-streaming` 界面、独立 npm 清单/锁文件、构建和测试入口。回放状态机迁入 `web/replay-state.mjs`，原始样本迁入 `fixtures/replay/`；旧报告与截图原样归入 `verification/streaming-prototype/`。
2. 删除重复保存图片的约 3.1 MB `fixture-data.json` 及依赖本机上游输出目录的准备脚本。改用 `web/prepare-fixtures.mjs` 从本仓库样本生成浏览器数据，构建时检查来源 SHA。
3. APK 构建只由 Gradle `preBuild → bundleWeb → web/build.mjs`（首次自动准备 npm 依赖） 打包网页；`npm run build:apk` 不再提前重复执行网页构建。独立浏览器预览仍用 `npm run build:web`。
4. Gradle 跟踪全部网页 `.mjs`、原始样本与生成目录；`build-info.json` 在复制 Android assets 前生成，使网页和 APK 内的构建信息一致。

## 冗余处理标记

以下是待合并项，不表示已经移除或验证过替代路径。

| 标记 | 位置与重复内容 | 后续合并条件 |
| --- | --- | --- |
| R1 | `web/verify-reorder.mjs` 的 7 项排序/导出检查在 `web/verify-editor-drag.mjs` 开头重复 | 抽出共享场景后保留快速排序入口和完整触屏入口，不能丢失触屏、校对与导出覆盖 |
| R2 | `tools/probe_modelscope.py`、`tools/verify_modelscope.py` 都获取目录并校验小文件 | 可共享请求与校验；前者验证严格 206 分段拼接，后者覆盖 200 回退，不能互相替代 |
| R3 | `tools/verify_model_loading.py`、`tools/verify_live_ocr.py` 重复 C ABI 声明、模型预校验和加载流程 | 提取测试宿主；分别保留历史缓存哨兵断言与真实流式事件断言，沿用 `ocr_runtime_checks.py` |
| R4 | `web/host.js` 与 `DocumentStore.java` 各自实现 DocumentIR 规范化、排序导出与 ZIP 状态恢复 | 先共享跨宿主契约样例，覆盖 partial/blank、原始 IR、校对和资源往返；不能直接删去某个宿主实现 |

真实模型生成与已有结果回放是两条不同流程，继续保留。旧 PDF/Office 格式解析器、原生历史 schema、Android 源码快照也不是仅凭名称就能删除的冗余代码。本次未改识别策略、原生快照、模型缓存或发布版本。

## 历史入口

- [DEVELOPMENT.md](DEVELOPMENT.md)：0.6.0 架构基线，其中未接入 OCR 的描述只适用于历史版本。
- [OCR-INTEGRATION-DESIGN.md](OCR-INTEGRATION-DESIGN.md)、[规格入口](OCR-INTEGRATION-SPEC.md)：保留最初设计和任务来源，不再作为当前实施状态。
- [RELEASE.md](RELEASE.md)：当前唯一发布操作说明；上传成功后不再复核远端资产。

执行界面回归前运行 `npm run build:web`。可用 `OCR_EVIDENCE_DIR=<新目录> npm run test:web` 保存本轮报告，避免覆盖历史截图。迁移验证和具体结果见 [.scratch/structure-cleanup/issues/01-cleanup.md](../.scratch/structure-cleanup/issues/01-cleanup.md)。
