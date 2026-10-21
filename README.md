# Camera Agent

Android camera agents for two Android generations.

| Directory | Minimum Android version | App name | Application ID |
| --- | --- | --- | --- |
| `android-8.0-camera-agent` | Android 8.0 (API 26) | Camera Agent | `io.github.ctxinf.cameraagent` |
| `android-4.0-camera-agent` | Android 4.0 (API 14) | Camera Agent legacy | `io.github.ctxinf.cameraagent.legacy` |

The Android 8.0 app uses Kotlin and Camera2. The legacy app uses Java and the original Camera API. Their distinct application IDs allow both apps to be installed together.

Build debug APKs with a configured Android SDK and a compatible JDK:

```sh
(cd android-8.0-camera-agent && ./gradlew assembleDebug)
(cd android-4.0-camera-agent/android && ./gradlew assembleDebug)
```

Set `ANDROID_HOME` or configure an untracked `local.properties` file for each Gradle project. Local SDK configuration, signing keys, and build output are excluded from Git.

Application IDs are stable release identities under the `io.github.ctxinf` namespace. Keep them independent of directory names, minimum Android versions, and UI branding. The legacy edition retains its `.legacy` suffix.

## HTTP path prefix

Both editions support **HTTP 路径前缀** in network settings. It defaults to empty,
so existing URLs continue to work. Setting `/camera/front` mounts every HTTP and
MCP endpoint below that path, for example:

```text
http://phone:8080/camera/front/snapshot.jpg
http://phone:8080/camera/front/video.mjpeg
http://phone:8080/camera/front/api/status
http://phone:8080/camera/front/api/camera
http://phone:8080/camera/front/mcp
```

The Android 8.0 edition also mounts its health endpoint and all API aliases under
the prefix. The legacy edition's MCP enable switch still applies. The homepage,
Android 8.0 notification and MQTT camera URLs include the configured prefix.
Changes apply immediately to new requests; existing streams continue until closed.
Unprefixed endpoints are no longer served when a prefix is set. Reverse proxies
must forward the prefix to the app rather than stripping it.

Leading/trailing slashes are normalized, repeated slashes are collapsed, and `/`
is treated as an empty prefix. Each segment accepts ASCII letters, digits and
`- _ . ~`; `.` and `..` segments, URLs, query strings, fragments and percent
escapes are rejected. Clear the setting to restore the original root URLs.

## Service controls

Both editions provide independent HTTP, MCP and MQTT checkboxes on the homepage
and in settings. HTTP defaults to enabled; MCP and MQTT default to disabled.
There is no master network switch. The background-service switch below the lens
controls decides whether the agent stays running after all app screens leave the
foreground. Turning it off does not interrupt services while the app is visible,
but stops them and releases camera/power resources after leaving the app.
Background-disabled and explicitly exited apps do not start on boot.

The lens row has highlighted Back / Front / Disabled controls. Disabled is a
device-only privacy gate: HTTP, MCP and MQTT cannot change camera access or
switch lenses while it is disabled. Snapshot requests return a static disabled
image, and MJPEG requests stream placeholder images without opening the camera.
The front-camera prohibition in settings also blocks remote front-lens changes.
The camera status line below MQTT separates the selected Back / Front / Disabled
mode from actual hardware use, including preview and warmup. The clockwise
rotation button cycles through 0°, 90°, 180° and 270° and shares its saved value
with the preview page. 0° is the default portrait image; sensor mounting correction
is internal and is not added to the angle shown to the user. Local preview and
HTTP images use the same orientation. Preview fits the rotated aspect ratio with
letterboxing instead of stretching; quarter turns exchange output width and height.

The red Force Exit button at the bottom of settings stops the service and process
and persistently suppresses automatic restarts until a manual launcher open.
The launcher animation lasts 900 ms and shows the app icon and name.

HTTP and MCP share the configured port and authentication, but their routes and
active connections are controlled independently. Disabling one leaves the other
running; disabling both releases the listener. Green means running/connected,
amber means enabled but starting or unavailable, and grey means disabled/paused.
HTTP/MCP connection counts appear on the right and refresh every second. They
count requests currently being handled, including streams, rather than remembered
clients or cumulative requests; short MCP requests may return to zero immediately.

MQTT fields can be edited and connection tests run while MQTT is disabled.
Both editions keep the local preview entry under settings.

## App identity assets

Both editions share the original camera-and-network mark in
[`assets/branding/camera-agent.svg`](assets/branding/camera-agent.svg).
Run `python assets/branding/generate-icons.py` with ImageMagick installed to
regenerate density-specific launcher PNGs, native adaptive icons and monochrome
layers. The legacy app keeps raster fallbacks for Android 4.0.

### Legacy MQTT 控制

Legacy（Android 4.0）控制主题保持 `<prefix>/<deviceId>/camera/set`，状态主题保持
`<prefix>/<deviceId>/state`。Home Assistant 自动发现提供 HTTP、MCP 开关、
摄像头模式、旋转与亮度控件。发送非保留 JSON 消息，例如：

```json
{"http_enabled":true,"mcp_enabled":false,"camera_mode":"back","rotation":90,"screen_brightness":50}
```

`camera_mode` 支持 `back` / `front` / `disabled`，选择镜头同时启用摄像头，
本地禁用前置的限制仍有效。`rotation` 支持 0/90/180/270 度。
`screen_brightness` 支持 0–100%，仅控制本应用窗口、优先于待机调暗，
进程结束后恢复默认，不修改系统亮度或唤醒屏幕。
状态包含以上五个同名字段；没有前台窗口时亮度报告系统设置。
旧 `facing`、`enabled` 指令仍支持。无效指令记录日志，处理后发布状态；
组合操作不提供事务回滚，HTTP/MCP 重配置及亮度应用异步完成。
