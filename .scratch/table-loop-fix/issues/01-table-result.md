# 恢复未通过校验的表格原文

Status: ready-for-agent

处理状态：已实施，本地回归通过；真机待验收。

根因：引擎 `finalize_region_recognition` 清空非 ok 表格的 content.text，Android 与浏览器此前直接展示空正文；生成期间则显示 raw，因而在完成时变坏。

任务：Android 与浏览器规范化时仅对非 ok 且正文为空的表格恢复 provenance.raw_output，不覆盖有效正文或原始 DocumentIR。验证 HTML 合并单元格、异常公式、校对、重开及 live→保存切换。
