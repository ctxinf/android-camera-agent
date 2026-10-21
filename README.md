# Android Camera Agent

## 自动发布 APK

推送 `v1.0.0` 或 `v1.0.0-beta.1` 格式的 tag 后，GitHub Actions 会测试并构建 Android 8.0+ 和 Android 4.0+ 两个版本，将签名 APK 和 SHA-256 校验文件上传到 GitHub Releases。带后缀的 tag 发布为预发布版本。

首次发布前，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容（Linux：`base64 -w 0 release.jks`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

请保管好正式签名密钥和备份，后续 APK 更新需要沿用相同签名；不要将密钥提交到仓库。两个应用使用不同 applicationId，可以共用签名密钥。未来接入 Google Play App Signing 时，应单独规划上传密钥。

```bash
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

APK 的 versionName 来自 tag（去掉 `v`），versionCode 为工作流运行序号加 100；请保留工作流名称和运行历史以保持版本号递增。当前本地构建默认版本号不变。缺少签名 Secrets 时工作流会明确失败。
