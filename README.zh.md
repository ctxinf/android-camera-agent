# Android Camera Agent

[English](README.md) · [完整说明](DETAIL.zh.md) · [下载 APK](https://github.com/ctxinf/android-camera-agent/releases)

把闲置安卓手机变成按需工作的摄像头代理。自己远程查看和操控，通过 MCP 交给 AI Agent 使用，或接入 Home Assistant。

![Android Camera Agent：让旧手机成为你、AI 和 Home Assistant 的摄像头](assets/branding/readme-hero.png)

<details>
<summary>实际截图</summary>

<p>
  <img src="assets/screenshots/01-main-dark.png" width="240" alt="完整主页：服务状态、摄像头控制与连接地址">
  <img src="assets/screenshots/03-interfaces-light.png" width="240" alt="浅色模式：HTTP、MCP 接口与展开的说明">
  <img src="assets/screenshots/04-camera-settings.png" width="240" alt="原生相机设置">
</p>

[全部截图](assets/screenshots/README.md)

</details>

## 能做什么

- **HTTP 访问与控制：**拍照、MJPEG 视频、查看状态、调整相机。
- **内置 MCP server：**让 AI Agent 查看画面、操控相机。
- **Home Assistant：**通过 **MJPEG IP Camera** 接入视频，通过 **MQTT 自动发现**添加实体和控件。
- **摄像头按需激活：**空闲后释放相机，减少耗电、发热与不必要的拍摄。

安卓原生开发，支持后台、熄屏运行，兼容低至 **Android 4.0** 的旧手机。

## 安装

从 [GitHub Releases](https://github.com/ctxinf/android-camera-agent/releases) 下载 APK：较新的手机选 **Android 8.0+ 版**，旧手机选 **Legacy（Android 4.0+）版**。

安装并授予相机权限，按应用显示的连接地址访问。[配置与接入步骤 →](DETAIL.zh.md)

## 和常见应用的区别

| | Camera Agent | DroidCam（Classic） | Android IP Camera |
| --- | --- | --- | --- |
| 主要用途 | 远程操控与自动化 | 电脑摄像头，音频 + 视频 | 网络摄像头与录像 |
| 接入方式 | HTTP · MCP · MQTT | 电脑客户端与摄像头驱动；HTTP 视频 | HTTP 操控；H.264 / MJPEG |
| 相机使用方式 | 按需打开，空闲释放 | 摄像头会话 | 推流与录像 |

参考：[DroidCam](https://www.dev47apps.com/)、[HTTP 视频](https://www.dev47apps.com/droidcam/connect/)、[Android IP Camera](https://github.com/DigitallyRefined/android-ip-camera)。[对比说明](DETAIL.zh.md#应用对比)
