# 下载仓库后无需获取或编译 MNN

Status: ready-for-agent

用户要求更新 GitHub，使下载项目后能用 Android Studio 直接构建，不另行下载或编译 MNN。

- 仓库普通 Git 文件内附固定提交的 Android arm64-v8a MNN/Express/OpenCV/LLM 预编译库、编译所需头文件和许可，不使用 LFS、子模块或构建时自动下载。
- Android Studio 和 Gradle 默认链接预编译包；保留明确选择源码构建的维护入口。
- 包保留原生产 Release/-O3/NDEBUG、Android API 26、NDK 28.1、分库和运行时行为；记录工件 SHA、build ID、来源及编译证据。
- 更新发布脚本、APK 检查和文档，默认发布流程也不获取源码。
- 从不包含 MNN 源码和旧 native 构建缓存的源码副本构建两版，验证真实后端链接、依赖打包、测试和 lint。提交推送到 GitHub main；不创建新 APK release。
