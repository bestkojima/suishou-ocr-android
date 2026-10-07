# 0.7.4 GitHub发布

账号：bestkojima。公开仓库：[suishou-ocr-android](https://github.com/bestkojima/suishou-ocr-android)。发布目标：[v0.7.4](https://github.com/bestkojima/suishou-ocr-android/releases/tag/v0.7.4)，预发布、开发签名、arm64-v8a，应用版本0.7.4-ocr／code11。

## 源码与构建

保留本项目Git历史，当前树中的旧APK停止跟踪（本地文件保留），新APK作为Release资产。包含实际使用的docprase源码快照79个文件及许可；MNN固定为公开提交baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6，依赖工具从GitHub成功获取并校验，MNN源码／缓存、SDK路径、密钥与权重不提交。源快照来源及各文件SHA-256在native/dependencies.json中。

最终APK使用仓库内docprase与从GitHub新获取的固定MNN重新构建，两版各62项JVM、lint0错误、arm64 JNI／C ABI／LLM依赖／无权重静态检查通过。最终打包包含第三方许可证和离线网页，网页标题更新为离线文档识别；发布前再次执行基础浏览器19组，0异常／外部请求。其余分辨率／识别／导航／流式32组见0.7.4分辨率交付记录。

模型速度与流式证据来自同源Linux真实推理；Android真机Bitmap、相机、推理性能和小字精度仍待验收。未声称已完成Windows或Android设备运行测试。

## 发布资产

- suishou-ocr-0.7.4-arm64.apk：普通版cn.local.ocr。
- suishou-ocr-0.7.4-lab-arm64.apk：测试版cn.local.ocr.test。
- SHA256SUMS.txt：最终上传文件的SHA-256。

APK静态核验身份见apk.json，构建／单元与依赖检查见checks.json、build.log、final-build.log、native-deps.log，发布资产校验见SHA256SUMS.txt。工作树和711个历史文本blob扫描未发现GitHub/HuggingFace令牌、AWS访问密钥或私钥；扫描不打印原始凭据。

远端推送及Release资产核对将在完成后追加记录。
