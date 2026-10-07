# 修复 Android 规范 PNG 被引擎拒收

Status: ready-for-agent

## 问题与范围

用户在安卓下载模型后识别图片失败，截图显示 `engine_ready:true`、返回码 `5`、`stage:decode`、`code:input_error`、`message:invalid input`。输入已保存且可重试；报错并不表示模型缺失。

Android 的 ImageInput 将输入规范为 ARGB_8888 PNG。docprase 的编码图片入口仅允许 1／3 通道，JNI 原先直接声明 PNG 交给该入口，因此含 Alpha 的规范图片会被拒收。修复 App 的输入适配，兼容已保存记录，不修改相邻引擎源码、不改变模型目录或扩大 OCR 输入范围。

## 验收

- [x] 实际 App JNI → C ABI 的最小 PNG 复现出现与截图一致的解码终态错误。
- [x] RGB PNG 保持原行为；RGBA／灰度 Alpha PNG 能识别，透明内容合成白纸背景。
- [x] 输出 DocumentIR 与资源与固定 RGB 对照一致，不通过修改引擎错误码伪造成功。
- [x] 通过实际 JNI 和生产模型执行新的 RGBA 图片识别并导出内容。
- [x] 普通／测试版构建为 `0.7.1-ocr`，JVM 回归及 APK 静态检查通过。
- [ ] 用户在原设备覆盖安装并对原记录重试，补齐该设备实际成功或后续失败证据。

## Comments

2026-10-07：已修复 JNI 适配并交付两版 APK。Linux 通过同一 JNI 源码和 C ABI 复现／验证；生产模型测试为桌面运行，不表示用户原设备已通过。完整记录见 [修复验证](../../../verification/android-image-decode/DELIVERY.md)。保留用户工作区中既有 `.scratch/android-real-ocr/README.md` 修改。
