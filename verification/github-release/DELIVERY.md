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

## 远端完成核对

源码和v0.7.4标签已推送；Release已发布，非草稿、预发布。发布源码提交60fab20ca54918dc5f8b99dcc268de0394fa3eac。GitHub资产digest与本地SHA一致；从GitHub重新下载两版APK和校验清单后，文件SHA再次一致，见upload-verification.json与remote-release.json。

另外从GitHub浅克隆发布源码，npm ci成功，使用仓库内docprase快照与已获取的固定MNN（显式mnnSourceRoot）从头构建两版APK、两版各62项JVM通过，见fresh-clone-checks.json和fresh-clone日志。该副本不依赖相邻docprase或本项目旧构建缓存；不同构建路径的APK不要求逐字节一致。发布资产仍使用前面已核验并上传的工作区最终产物。

本次发布为用户明确授权；没有额外创建PR、GitHub任务或修改相邻仓库。发布后的校验记录以文档提交补充到main，版本标签保留构建源码身份。
