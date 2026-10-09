# 一键构建、提交与发布

在项目根目录使用 `tools/release.py`。脚本将源码、版本、改进说明及两份 APK 一起发布到现有 GitHub 仓库。默认是预发布。

## 最常用命令

```bash
python3 tools/release.py --change "修复某个问题并说明现在的行为"
```

从 `app/build.gradle` 读取版本并递增 patch。例如当前 `0.7.7-ocr`／14，下一次为 `0.7.8-ocr`／15，标签为 `v0.7.8`。可以用多个 `--change` 写多项改进：

```bash
python3 tools/release.py 0.7.8 --change "第一项改进" --change "第二项改进"
```

已有完整发布说明时：

```bash
python3 tools/release.py 0.7.8 --notes-file docs/my-release-notes.md
```

说明不得为空。`--change` 自动生成含安装方法和设备待验收项的中文说明；`--notes-file` 原样使用文件内容，应自行写清改进、测试结果和当前限制。说明保存到 `docs/releases/v版本.md` 并随源码提交。

只看计划，不构建、不改文件、不提交、不联网：

```bash
python3 tools/release.py --change "本次改进" --dry-run
```

Windows 将 `python3` 换为 `python`；脚本自动使用 `gradlew.bat` 和 Windows 构建工具。Linux／WSL 使用 `./gradlew`。

## 运行前准备

- 安装 README 列出的 JDK 17、Node.js、Python 3、Android SDK 35、NDK 和 CMake，以及 Git 与 GitHub CLI（`gh`）。SDK 通过 `local.properties`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 配置。
- 执行一次 `gh auth login`，账户须能推送仓库并创建 release。Git 本身的 HTTPS／SSH 推送凭据也须可用，设置好 `user.name` 和 `user.email`。
- 用原构建环境的 `debug.keystore` 签名。默认在用户目录的 `.android/debug.keystore`，不提交到仓库。脚本要求证书与现有已发布 APK 一致，换机器时须保留原密钥，才能覆盖安装并保留模型和记录。
- 在工作分支执行，先解决合并冲突。**脚本会用 `git add -A` 提交项目全部未忽略改动**，包括原本已暂存和未暂存的修改；发布前整理好这些改动。

缺少前端依赖时自动执行 `npm ci`；MNN 使用项目固定版本，默认安装到 `native/MNN`，本工作区也允许复用现有固定版本的相邻 `MNN`。不需要下载模型权重来构建 APK。

## 自动执行的流程

1. 检查版本、Git 工作分支、冲突、远端和 GitHub 登录。版本不能倒退，旧标签不能覆盖。
2. 更新 `app/build.gradle` 的 `versionName`／`versionCode`、README 当前版本和下载入口，保存本次发布说明。显式指定已在源码写入的同一版本时，保留 versionCode；适用于修正构建失败后重试。
3. 执行两版 JVM 测试、lint 和 `assembleUserDebug`／`assembleLabDebug`。保持应用标识及 Debug 签名，原生仍为 Release／`-O3`／`NDEBUG`，mmap 默认值不变。
4. 在上传前检查 APK 的 ABI、原生编译、动态库依赖、裁剪资源、权重排除、应用标识、版本和原签名兼容。此步骤不执行设备推理。
5. 整理普通版、测试版，并由本地 APK 生成 `SHA256SUMS.txt`；提交源码，用普通的原子推送将该提交和标签推到 `origin/main`。
6. 一次调用 GitHub CLI 创建 release，上传三项资产和改进说明。**上传命令返回成功即报告发布成功并结束。**

最后一步之后不查询远端 release／资产，不下载，不比较远端或下载文件的 SHA，也不做额外验收。`gh release create --verify-tag` 只是创建前要求标签已经推送，不是上传后的复核。SHA 文件用于给安装者提供校验值。

浏览器交互、真实 OCR、Android 真机效果、性能与内存按本次改动另行验收，不由脚本的构建通过代替。历史 v0.7.7 等版本的上传后复核记录保留，不作为后续流程。

## 输出与续传

本地输出在 `artifacts/releases/v版本/`，该目录被 Git 忽略：

```text
suishou-ocr-0.7.8-arm64.apk
suishou-ocr-0.7.8-lab-arm64.apk
SHA256SUMS.txt
notes.md
apk-checks.json
identity-checks.json
state.json
```

`state.json` 记录来源提交、版本、仓库、推送和发布进度。源码提交完成后，若推送或上传失败，保留上述文件并继续：

```bash
python3 tools/release.py --resume v0.7.8
```

续传使用原提交、原 APK 和原发布说明，不重建、不重复提交；已成功的 Git 推送也不重复。完成后再次续传只读取本地完成状态，直接显示原发布地址，不联网复核。

提交前失败时没有可续传的状态；修正问题后用**相同的显式版本**和原改进说明重跑，例如 `python3 tools/release.py 0.7.8 --change "本次改进"`。已修改源码和生成的文件保留，不自动回退。

若目标分支有新提交，普通推送会拒绝覆盖，先自行整合；脚本不强推、不删除旧 release、不覆盖旧标签。如果命令在 GitHub 已完成上传后被系统中断、来不及保存成功状态，续传可能报告 release 已存在，此时保留已发布版本，不能盲目删除或重传。

本机 GitHub CLI 2.45.0 在正常上传失败时会清理本次创建的临时草稿，因此可以用上述命令重新上传；进程被直接终止或草稿清理失败时可能遗留草稿，应先人工处理该未完成发布。该行为见 [GitHub CLI 对应版本源码](https://github.com/cli/cli/blob/v2.45.0/pkg/cmd/release/create/create.go#L425-L473)。

## 其他参数

`--remote` 和 `--branch` 可修改推送目标，默认 `origin`／`main`。仓库由该远端推送地址读取，仅支持 github.com 的 HTTPS／SSH 地址。续传采用原状态中的远端和分支。

只有用户明确宣布正式版才使用 `--stable`，例如：

```bash
python3 tools/release.py 1.0.0 --stable --change "正式版改进与验收结果"
```

默认始终创建预发布，不自动升级到 1.0.0；不传 `--stable` 时拒绝发布 1.0.0 及以上版本。

脚本自身的流程测试使用临时 Git 仓库和模拟构建／上传，不创建真实 release：

```bash
python3 -m unittest discover -s tools/tests -p 'test_release.py' -v
```
