# 0.7.4：图片解码与分辨率

用户怀疑真实 OCR 过慢与未缩小原图有关。此前模型边界已经有限制，缺口在 Android 原图规范化：完整解码、两份像素数组、EXIF 校正及全尺寸 PNG 保存；最大 6400 万像素只是拒绝上限。大图也会使更多区域接近视觉预算上限。

## 实施

- 默认均衡：长边≤4096、≤800万像素；快速：长边≤2560、≤400万像素；原图不缩小；所有策略继续拒绝超过6400万像素。小图不放大。
- bounds → 最大可覆盖目标的2次幂 inSampleSize → 精确按比例缩放 → EXIF 1～8 → 原子保存PNG。方向1省掉两份像素数组；图片处理内存不足时报告可重试错误，原文件仍保留。实现遵循 [Android 大图解码说明](https://developer.android.com/topic/performance/graphics/load-bitmap)。
- `imagePreparation` 保存版本、策略、原图／实际解码／规范尺寸、采样、EXIF和耗时；OcrEngine 输出同一份记录。同策略规范输入复用；老待识别图片补做处理。
- 保留 rawInput，重新识别另存记录并复制原始文件；换策略从原始文件重建，不在已经缩小的图上反复缩小。缺少原文件的老记录只能使用保存的source。
- 每项作业开始固定策略；设置对下次识别生效。处理PNG、页面、资源与native坐标使用同一规范尺寸；旧已完成记录不改写。解码／压缩不持有文档锁，提交重新读取当前状态，保留期间到达的取消状态。
- native版面800×800、默认区域65,536～313,600像素、视觉缺失定向重试、生成预算、CPU计划和真实流式输出沿用现有实现；默认区域像素上限不意味着长边≤560。

## 真实模型计时

Linux x86_64 VirtualBox、4个逻辑处理器，Android同源加载／流式适配库，实际PP-DocLayoutV3与OvisOCR2。三种尺寸顺序执行；每种创建引擎后识别两次相同图片，无构建或浏览器检查同时运行。加载在整页计时外；首次表示新引擎首次推理，不表示磁盘缓存已清空。包含native PNG解码、版面、裁剪、视觉、prefill、decode及资源生成；**不包含Android Bitmap规范化，不是平板性能**。

输入由1000×1380的现有真实识别测试图放大4倍形成合成大图；缩小使用BILINEAR，与Android精确缩放方式对应。这不是新增真实高分辨率小字照片的精度评测。

| 输入 | 新引擎首次整页 | 连续第二张 | 第二张首次正文 | 视觉token合计 |
| --- | ---: | ---: | ---: | ---: |
| 4000×5520，2208万像素 | 30.26s | 20.67s | 6.15s | 1851 |
| 2407×3322，约800万像素 | 24.48s | 12.87s | 2.47s | 1039 |
| 1702×2349，约400万像素 | 23.69s | 11.99s | 1.80s | 950 |

均衡连续识别耗时相对大图减少37.73%。第二张的区域视觉耗时合计3.805s→1.774s，prefill8.965s→7.395s。输入分辨率影响预处理，也影响实际视觉token和prefill，不能只靠加快逐字展示解决。

三种尺寸均输出10个展示块，正文去除模型添加的标题前缀后相同，表格HTML逐字相同。原图两次公式输出为“## E=mc²”，公式语法校验未通过，页标partial；均衡和快速两次均为可解析LaTeX，页标ok。不是所有模型输出逐字一致；较小图片没有在这个样本上破坏表格，但不能据此保证真实小字精度。原图partial会触发既有native页末恢复重建，约0.13秒，已包含在总耗时；这是页末恢复，不是每区域重载。

`performance.json` 保存尺寸、各次计时、视觉token、阶段合计、文字／表格对比、公式、状态；`large/`、`balanced/`、`fast/` 保存原生manifest、DocumentIR、Markdown与流式时间线。所有结果的raster_size与对应输入尺寸相同，块边界在页面内，流式正文与最终保存的attempt一致。

重跑命令（三种尺寸依次执行）：

```bash
python3 tools/benchmark_ocr.py --library /tmp/android-ocr-loading-host/build/docprase/libdococr_c.so --threads 4 --runs 2 --allow-partial --input verification/image-resolution/large.png --output /tmp/ocr-resolution-large
python3 tools/benchmark_ocr.py --library /tmp/android-ocr-loading-host/build/docprase/libdococr_c.so --threads 4 --runs 2 --allow-partial --input verification/image-resolution/balanced.png --output /tmp/ocr-resolution-balanced
python3 tools/benchmark_ocr.py --library /tmp/android-ocr-loading-host/build/docprase/libdococr_c.so --threads 4 --runs 2 --allow-partial --input verification/image-resolution/fast.png --output /tmp/ocr-resolution-fast
```

`--allow-partial`仅供诊断保存局部失败／未校验结果；默认仍要求完整成功。

## 检查

- 原来的尺寸决策以原图尺寸进入真实normalize调用，4800万像素相机图和快速／长图限制检查失败，见red.log；修复后通过。
- 两版JVM各62项，0失败／错误／跳过：大图策略、采样不低于目标、小图不放大、EXIF像素变换、原文件复制、规范输入缓存、换策略从原文件重建、老记录更新、取消状态不被输入元数据提交覆盖及已有真实结果／导出回归。**JVM不运行Android Bitmap解码器**，缓存／迁移测试使用明确Normalizer边界；原始EXIF像素变换为纯Java测试。
- 浏览器基础19、分辨率／模型页9、识别桥11、导航8、流式4，共51组。覆盖三档设置与持久化、AndroidHost请求（明确桥模拟）、320px无横向溢出、原有回放选择、真实流快照渲染。结果见results.json及各ui.json／日志。
- 两版lint各0错误、11条既有警告；两版0.7.4-ocr、versionCode11 APK构建和arm64／C ABI／JNI／LLM依赖静态检查通过，SHA见apk.json。

## 设备待验收

ADB当前没有设备。实际Android Bitmap、JPEG/HEIC预采样、Exif旋转／镜像与透明PNG缩放、峰值内存、耗时，以及真实平板照片特别是密集小字，均需要设备实测，不能用本次合成图替代。覆盖安装同类型0.7.4 APK保留下载模型；设置默认均衡，小字模糊可切原图并“重新识别”。日志：`adb logcat -s OcrEngine`，重点看image preparation的rawWidth/rawHeight、sampleSize、decodedWidth/decodedHeight、width/height与elapsedMs，并分别记录首次与连续识别。

本轮及之前加载／流式／引擎复用改动留在工作区，未提交；相邻docprase和MNN源码未改动。
