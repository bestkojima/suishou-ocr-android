# 任务跟踪：本地 Markdown

本项目的任务和规格保存在本地 `.scratch/`。这里是当前 Android 项目的任务系统，不自动发布到相邻 docprase 仓库。

## 文件约定

- 每个功能独立目录：`.scratch/<feature-slug>/`。
- 规格文件：`.scratch/<feature-slug>/spec.md`。
- 实施任务：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 编号，每个任务一个文件，不合并为单一任务文件。
- 规格和任务的分流状态使用靠近文件顶部的 `Status:` 行，标签定义见 [分流标签](triage-labels.md)。
- 评论和后续讨论追加到文件末尾的 `## Comments`。
- 文档和任务正文默认使用简体中文；技能模板要求的固定标题或句式可保留原文。

## 技能操作

技能要求“发布到 issue tracker”时，在相应功能目录创建 Markdown 文件；要求“读取相关任务”时，读取用户指定的本地文件或编号对应的任务。不要调用 GitHub/GitLab 创建远端 Issue。

当前 `to-spec` 规格发布到 `.scratch/android-real-ocr/spec.md`，状态为 `ready-for-agent`。

## 决策地图

`wayfinder` 地图使用 `.scratch/<effort>/map.md`，子任务保存在其 `issues/` 目录，按编号分别记录。

地图子任务用 `Type:` 记录 research/prototype/grilling/task，用 `Status: claimed` 或 `Status: resolved` 记录决策处理状态；这些是地图操作状态，与上述分流标签用途不同。

依赖使用 `Blocked by: NN, NN`；全部依赖 resolved 后才能推进。认领时先保存 claimed；解决时追加 `## Answer`，保存 resolved，并将结论及子任务链接追加到地图的 Decisions-so-far。

