# Android Camera Agent — 完整说明

[English](DETAIL.md) · [项目简介](README.zh.md) · [下载 APK](https://github.com/ctxinf/android-camera-agent/releases)

用闲置手机远程看一眼房间、给 AI Agent 配一只眼睛，或把摄像头和控制实体接入 Home Assistant。手机本身就是服务端，使用者和自动化工具直接连接它。

[安装](#安装) · [按需使用相机](#按需使用相机) · [HTTP](#http) · [MCP](#mcp) · [Home Assistant](#home-assistant) · [设置与排障](#设置与排障) · [应用对比](#应用对比) · [构建与发布](#构建与发布)

## 安装

从 [GitHub Releases](https://github.com/ctxinf/android-camera-agent/releases) 下载 APK。

| 版本 | 系统要求 | APK 文件名 | 相机实现 |
| --- | --- | --- | --- |
| Android 8.0+ 版 | Android 8.0 及以上 | `camera-agent-android8-<tag>.apk` | Camera2 |
| Legacy 版 | Android 4.0 及以上 | `camera-agent-android4-<tag>.apk` | Camera1 |

手机支持时优先选 8.0+ 版。两版均为安卓原生应用，包名不同，可以同时安装；如果在同一台手机上同时运行，需要使用不同的 HTTP 端口。

1. 安装 APK，按提示授予相机权限。
2. 让手机和访问设备连到同一个网络。
3. 打开应用，启用后台服务、需要的 HTTP 或 MCP 服务，并选择镜头。
4. 从「连接与接口」复制地址，先在浏览器打开 `/snapshot.jpg` 看一张照片。

HTTP 默认端口为 `8080`。下文的 `http://PHONE_IP:8080` 都要换成应用实际显示的地址；设置了路径前缀时也要带上。根地址不是网页控制台，请使用列出的具体接口。

## 按需使用相机

服务开启表示手机可以接收请求，并不表示相机一直在拍。

- 请求一张快照时，打开相机拍照，随后按设置的保活时间释放相机。
- 观看 MJPEG 或使用本地预览时，相机会持续工作。
- 所有使用者断开、保活时间结束后，相机关闭。

空闲时减少相机和编码工作，有助于降低耗电、发热，也减少不必要的拍摄。实际温度和续航取决于手机及使用方式。持续播放视频或频繁刷新缩略图的仪表盘，会不断请求相机。

「待机」表示随时可以响应请求。「禁用」会阻止真实画面访问，图片接口改为返回占位图。HTTP 和 MCP 不能远程重新开启这个本地开关。MQTT 是另一条控制入口，可以改变摄像头模式，因此只把 broker 访问权交给可信客户端。

## HTTP

两版都有这些接口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/snapshot.jpg` | 获取一张 JPEG 图片 |
| `GET` | `/video.mjpeg` | 观看 MJPEG 视频流 |
| `GET` | `/api/status` | 读取设备与相机状态 JSON |
| `GET`、`POST` | `/api/camera` | 读取相机配置或切换镜头 |
| `POST` | `/mcp` | MCP 请求，需单独启用 MCP |

图片地址可以直接在浏览器或支持 MJPEG 的播放器中打开。脚本示例：

```bash
curl http://PHONE_IP:8080/api/status
curl http://PHONE_IP:8080/snapshot.jpg --output snapshot.jpg
curl --request POST http://PHONE_IP:8080/api/camera \
  --header 'Content-Type: application/json' \
  --data '{"facing":"back"}'
```

启用 HTTP 基本鉴权后，在 curl 中加 `--user YOUR_USERNAME`，随后按提示输入密码；浏览器打开图片时也会要求登录。

8.0+ 版的 `/api/camera` 还接受 `targetWidth`、`targetHeight`、`targetFps`、`jpegQuality`、`rotationOffset`、`retentionGracePeriodSec` 等字段，并提供 `POST /api/v1/warmup`（例如 `{"durationSec":5}`）和 `GET /health`。Legacy 的 HTTP 相机配置接口只用于切换镜头。具体参数以所装版本的应用内接口说明和 MCP 工具声明为准。

## MCP

打开应用中的 **MCP** 开关，端点是 `http://PHONE_IP:8080/mcp`，与 HTTP 共用路径前缀和基本鉴权设置。HTTP、MCP 两个开关分别控制各自的接口。

在 Agent 的 MCP 客户端里填入这个 URL 和认证信息，再让它列出工具。客户端必须能访问手机所在网络；运行在云端的 Agent 无法直接访问家里的局域网 IP，需要先打通网络。

| 工具 | 用途 |
| --- | --- |
| `get_snapshot` | 拍照，把 JPEG 图片内容返回给 Agent |
| `get_status` | 读取相机与设备状态 |
| `set_camera_facing` | 切换前后镜头 |
| `set_camera_config` | 修改画面配置，两版参数不同 |
| `warmup_camera` | 提前预热相机，仅 8.0+ 版 |

可以直接让 Agent「拍一张照片，描述你看到了什么」，或「切换到后置镜头，再拍一张」。

8.0+ 版通过 HTTP POST 处理 JSON-RPC，报告的 MCP 修订号为 `2024-11-05`；Legacy 支持 `2025-06-18`、`2025-11-25`、`2026-07-28`。是否能连接取决于客户端接受的协议修订和传输方式。两版虽然会列出 `set_camera_enabled`，但都会拒绝远程调用；请在手机上开启相机访问。

查看 8.0+ 版的工具列表：

```bash
curl http://PHONE_IP:8080/mcp \
  --header 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Legacy 客户端应先协商支持的 MCP 修订，再列出工具。按返回的 schema 填参数，不要把两版的配置字段混用。

## Home Assistant

### 通过 MJPEG IP Camera 接入画面

在 **设置 → 设备与服务 → 添加集成** 中选择 [MJPEG IP Camera](https://www.home-assistant.io/integrations/mjpeg/)，填入：

| 配置项 | 值 |
| --- | --- |
| MJPEG URL | `http://PHONE_IP:8080/video.mjpeg` |
| 静态图片 URL | `http://PHONE_IP:8080/snapshot.jpg` |
| 用户名 / 密码 | 应用中设置的 HTTP 凭据，未开启鉴权时留空 |

配置过路径前缀时，两条 URL 都要带上。持续观看或频繁刷新缩略图会不断激活相机；希望手机多待机，就尽量按需取图。

### 通过 MQTT 添加实体和控件

让 Home Assistant 的 MQTT 集成与手机连接同一个 broker。在应用中填写 broker 地址、端口、账号密码、主题前缀，再启用 MQTT。8.0+ 版的每台手机应使用不同的客户端标识；Legacy 会生成自己的设备 ID。

应用向 `homeassistant/` 下发布自动发现消息。Home Assistant 随后可以显示设备状态，并提供 HTTP、MCP、摄像头模式、旋转和屏幕亮度控件。具体传感器因版本而异。MQTT 用来传状态和控制，视频仍通过上面的 HTTP 地址接入。

自己写自动化时，注意两版的主题不同：

| | Android 8.0+ 版 | Legacy 版 |
| --- | --- | --- |
| 状态 | `<prefix>/<clientId>/status` | `<prefix>/<deviceId>/state` |
| 控制 | `<prefix>/<clientId>/set` | `<prefix>/<deviceId>/camera/set` |
| 控制结果 | `<prefix>/<clientId>/set/result` | 无独立结果主题 |

两版均接受下面的控制格式：

```json
{"http_enabled":true,"mcp_enabled":true,"camera_mode":"back","rotation":90,"screen_brightness":50}
```

只发送需要修改的字段即可。`camera_mode` 可选 `back`、`front`、`disabled`；`rotation` 可选 `0`、`90`、`180`、`270`；亮度为 `0`–`100`，只影响应用窗口，不会唤醒屏幕。请发送非保留消息，避免重放旧操作。8.0+ 版会忽略保留指令，并在结果主题报告 `success` 或 `error`；组合指令不提供事务回滚。

## 设置与排障

| 设置 | 解决什么问题 |
| --- | --- |
| 镜头 / 禁用前置摄像头 | 选择镜头，或限制前置镜头访问 |
| 分辨率、帧率、JPEG 质量 | 在清晰度、带宽、编码负载之间取舍 |
| 拍照后相机保活时长 | 连拍更快，但相机关闭得更晚 |
| 亮屏保持时间 / 静息亮度 | 调整应用空闲时的屏幕行为 |
| 忽略电池优化 | 减少熄屏后服务被系统限制的情况 |
| HTTP 绑定 IP、端口、路径前缀 | 指定 HTTP 和 MCP 的访问地址 |
| HTTP 基本鉴权 | 给图片、控制和 MCP 请求加上账号密码 |
| 语言 / 主题 | 选择界面语言与外观 |

后台和熄屏运行受手机省电策略影响。按机型允许后台活动或自启动，保持应用的后台服务开启，避免强制停止应用。

**连不上：**检查地址、端口冲突、Wi-Fi 设备隔离和服务开关。**404：**检查路径前缀和对应的 HTTP/MCP 开关。**401：**检查账号密码。**只有占位图：**检查本地相机访问开关。**锁屏后失联：**检查电池优化和厂商后台限制。**发热或带宽偏高：**降低分辨率、帧率或 JPEG 质量，并检查是否有客户端持续拉流。

基本鉴权不会加密 HTTP 流量。请在可信局域网使用，跨网访问可通过 VPN。8.0+ 版的 `/health` 不要求鉴权。直接使用 HTTP/MCP 不需要云账号，MQTT 则需要自行配置 broker。

## 应用对比

[DroidCam Classic](https://www.dev47apps.com/) 主要把手机当作电脑摄像头：电脑客户端安装音视频驱动后连接手机。它也有不需要电脑客户端的[网页 / MJPEG 模式](https://www.dev47apps.com/droidcam/connect/)。

[DigitallyRefined 的 Android IP Camera](https://github.com/DigitallyRefined/android-ip-camera) 提供 H.264/MJPEG、网页控制、HTTPS 和录像，也支持后台、熄屏推流，可以通过 MJPEG 接入 Home Assistant。

Camera Agent 的重点是让人、MCP Agent 和 MQTT 自动化按需请求相机，并在空闲后释放硬件。当前画面输出为 JPEG/MJPEG，不提供电脑摄像头驱动或音频流。后台运行是几款应用共有的能力，不作为独占卖点。

## 构建与发布

准备 JDK 17、Android SDK platform 36 和 Build Tools 35.0.0。在仓库根目录运行：

```bash
(cd android-8.0-camera-agent && bash ./gradlew testDebugUnitTest assembleDebug)
(cd android-4.0-camera-agent/android && bash ./gradlew testDebugUnitTest assembleDebug)
```

APK 在各自项目的 `app/build/outputs/apk/debug/` 下。8.0+ 版使用 Kotlin / Camera2；Legacy 使用 Java / Camera1，没有运行时依赖。

维护者发布时，[GitHub Actions 工作流](.github/workflows/release.yml) 会在推送 `v1.0.0` 或 `v1.0.0-beta.1` 格式的 tag 后，测试并构建两版 APK。首次发布前配置以下 Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容；Linux：`base64 -w 0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

保管好签名密钥和备份，不要提交到仓库。后续更新必须沿用相同签名；两版应用包名不同，可以共用签名密钥。未来接入 Google Play App Signing 时另行规划上传密钥，并在商店发布前重新检查 SDK target。

```bash
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

工作流会上传签名 APK 和 `SHA256SUMS.txt`；带后缀的 tag 发布为预发布版本。`versionName` 来自去掉 `v` 的 tag，`versionCode` 为工作流运行序号加 100。保留工作流名称和运行历史，保证版本号递增。本地默认版本号不变；缺少签名 Secrets 时工作流会明确失败。
