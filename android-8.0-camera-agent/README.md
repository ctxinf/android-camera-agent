
### Android 8 MQTT 远程控制

状态主题为 `<prefix>/<clientId>/status`，控制主题为 `<prefix>/<clientId>/set`，
结果主题为 `<prefix>/<clientId>/set/result`。`prefix` 与 `clientId` 使用 MQTT 设置中的值。
状态主题加入了设备 ID，已有订阅 `<prefix>/status` 的自动化需要更新。
Home Assistant 自动发现会提供 HTTP、MCP 开关、摄像头模式、旋转和屏幕亮度控件。

控制消息是 JSON，可单独或组合发送：

```json
{"http_enabled":true,"mcp_enabled":false,"camera_mode":"back","rotation":90,"screen_brightness":50}
```

- `http_enabled`、`mcp_enabled`：布尔值，保存并立即刷新监听及受影响连接。两者关闭后 MQTT 仍可用于恢复。
- `camera_mode`：`back`、`front`、`disabled`。选择镜头会启用摄像头，`disabled` 关闭摄像头；本地禁用前置限制仍有效。
- `rotation`：0、90、180、270，表示摄像头输出旋转偏移，保存到现有摄像头配置。
- `screen_brightness`：0–100，设置本应用窗口亮度，并优先于待机调暗；仅当前进程有效，不修改系统亮度、不唤醒屏幕。状态报告当前前台窗口亮度，无前台窗口时报告系统亮度设置。

请发送非保留消息；客户端忽略保留指令，避免重连后重放控制。
结果消息包含 `success`，失败时包含 `error`；随后发布实际状态。组合指令不提供事务回滚。
