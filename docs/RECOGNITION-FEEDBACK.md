# 识别结果反馈

结果页的「反馈识别问题」附上本次实际识别图片、原始 `document.json` 和用户描述。分类可不选（默认「其他 / 不确定」）；自动记录应用版本、模型、Android 版本和设备型号。这里只处理识别样本，不收集应用日志或其他历史文档。

## 当前可用流程

用户填好描述后，可保存 ZIP，或调用 Android 系统分享选择飞书等应用。系统分享面板能否列出飞书取决于设备安装情况；打开分享面板不代表已发送。

反馈包先冻结到应用私有 `files/feedback/`，重试复用同一份文件和反馈编号。包内为：

- `input.<原图片扩展名>`：实际传给识别引擎的图片；拍照裁剪后使用裁剪图。
- `document.json`：本轮整页原始 DocumentIR，逐字节保留，不把人工校对或区域替换结果写入该文件。
- `description.txt`：用户描述。
- `feedback.json`：分类、描述、应用/模型/设备信息、作业编号、反馈编号及附件 SHA256。

草稿和已生成包编号在关闭面板后保留。编辑描述或分类会生成新的反馈包；原包仍留在私有存储，卸载或清除应用数据会移除。删除文档不会删除已经冻结的反馈包。

## 接收渠道配置

用户目前尚未提供 GitHub/飞书接收服务。因此默认 APK 的自动提交地址为空，界面明确提示尚未配置，并只提供保存、分享。当前没有部署服务或创建任何外部 Issue。

提供接收服务后，构建时设置两个公开 HTTPS 地址：

```powershell
.\gradlew.bat assembleUserDebug assembleLabDebug `
  -PfeedbackEndpoint=https://feedback.example.com/recognition `
  -PfeishuFeedbackEndpoint=https://feedback-backup.example.com/recognition
```

以上为占位示例。GitHub token、飞书 app secret 和机器人 webhook 密钥不得写入 APK；应由接收服务保管。主服务可以保存附件并创建 GitHub Issue；飞书备用服务接收同样的包并转交飞书。普通飞书表单链接或机器人 webhook 不直接满足本接口，需要收到真实目标后完成适配。

### 接口契约

`POST` 原始 ZIP，`Content-Type: application/zip`，`Idempotency-Key` 和 `X-Feedback-Id` 均为反馈 UUID。服务必须完整保存附件后返回 2xx 和 JSON：

```json
{"feedbackId":"与请求一致的 UUID","status":"received"}
```

主渠道失败或回执不匹配，自动尝试备用渠道；两者都失败则保留本地包，允许重试/分享。只有匹配的成功回执才显示已提交。客户端不跟随重定向，连接超时 10 秒、读取超时 20 秒；反馈包不超过 256 MB，回执不超过 64 KiB。

两个服务需按相同反馈 UUID 去重，并共享去重记录或支持运营侧合并，以处理「主渠道已收到，但回执丢失后转备用渠道」的情况。客户端的稳定编号不能单独保证跨服务只创建一条 Issue。

## 已验证与待验证

JVM 测试验证图片/原始 JSON 冻结、描述校验、过期作业拒绝、回执校验及主备切换；浏览器模拟桥测试验证草稿、保存、重试和提交状态。真机生成的包与该次识别图片/原始 JSON 哈希一致，系统分享面板正常打开。

真实 GitHub/飞书收件、鉴权、限流及跨渠道去重，需接收服务提供后再做端到端联调。
