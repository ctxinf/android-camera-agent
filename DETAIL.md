# Android Camera Agent — User guide

[中文](DETAIL.zh.md) · [Overview](README.md) · [Download APK](https://github.com/ctxinf/android-camera-agent/releases)

Use a spare phone to check a room remotely, give an AI agent a camera, or add a camera and device controls to Home Assistant. The phone hosts the services; clients connect directly to it.

[Install](#install) · [On-demand camera](#on-demand-camera) · [HTTP](#http) · [MCP](#mcp) · [Home Assistant](#home-assistant) · [Settings and troubleshooting](#settings-and-troubleshooting) · [Comparison](#comparison) · [Build and release](#build-and-release)

## Install

Download an APK from [GitHub Releases](https://github.com/ctxinf/android-camera-agent/releases).

| Version | Android | APK filename | Camera backend |
| --- | --- | --- | --- |
| Android 8.0+ | 8.0 and newer | `camera-agent-android8-<tag>.apk` | Camera2 |
| Legacy | 4.0 and newer | `camera-agent-android4-<tag>.apk` | Camera1 |

Choose the 8.0+ version when your phone supports it. The two versions are native Android apps with different package IDs and can be installed side by side. Their HTTP ports must differ if both run on the same phone.

1. Install the APK and allow camera access when prompted.
2. Connect the phone and your viewing device to the same network.
3. Open the app, enable its background service and the HTTP or MCP service you want to use, and select a lens.
4. Copy the relevant URL from **Connections and Interfaces**. Start with `/snapshot.jpg` in a browser.

HTTP defaults to port `8080`. The examples below use `http://PHONE_IP:8080`; replace it with the address shown in your app, including any configured path prefix. The base URL itself is not a web dashboard: use the listed endpoints.

## On-demand camera

Enabling a service makes the phone available for requests. It does not keep the camera capturing continuously.

- A snapshot opens the camera, captures a frame, then lets it close after the configured keep-alive period.
- An MJPEG viewer or local preview keeps the camera active while in use.
- When all consumers disconnect and the keep-alive period ends, the camera is released.

This reduces camera and encoder activity while idle, helping with battery use, heat, and privacy. It does not promise zero heat or a particular battery life. A dashboard that continually refreshes images or holds a video stream open will keep requesting the camera.

**Standby** means ready to respond. **Disable** denies access to real camera frames; image endpoints return a placeholder. HTTP and MCP cannot turn this local camera gate back on. MQTT camera controls are a separate remote control path and can change camera mode, so give broker access only to clients you trust.

## HTTP

Both versions provide these endpoints:

| Method | Path | Use |
| --- | --- | --- |
| `GET` | `/snapshot.jpg` | One JPEG image |
| `GET` | `/video.mjpeg` | Live MJPEG stream |
| `GET` | `/api/status` | Device and camera status as JSON |
| `GET`, `POST` | `/api/camera` | Read camera settings or switch the selected lens |
| `POST` | `/mcp` | MCP requests; enable MCP separately |

Open image URLs in a browser or an MJPEG-compatible viewer. For scripts:

```bash
curl http://PHONE_IP:8080/api/status
curl http://PHONE_IP:8080/snapshot.jpg --output snapshot.jpg
curl --request POST http://PHONE_IP:8080/api/camera \
  --header 'Content-Type: application/json' \
  --data '{"facing":"back"}'
```

When HTTP basic authentication is enabled, add `--user YOUR_USERNAME` to curl; it will prompt for the password. Browser image requests will also ask for credentials.

The 8.0+ version additionally accepts camera configuration fields such as `targetWidth`, `targetHeight`, `targetFps`, `jpegQuality`, `rotationOffset`, and `retentionGracePeriodSec` at `/api/camera`. It also provides `POST /api/v1/warmup` with `{"durationSec":5}` and `GET /health`. Legacy's HTTP camera configuration endpoint changes lens selection only. Use the interface descriptions in the app and the MCP tool schemas for the version you installed.

## MCP

Enable **MCP** in the app. Its endpoint is `http://PHONE_IP:8080/mcp`, with the same path prefix and basic authentication settings as HTTP. The HTTP and MCP toggles control their own routes independently.

Configure your agent's MCP client with this URL and credentials, then ask it to list tools. The client must be able to reach the phone; a cloud-hosted agent cannot reach a private LAN address without a network connection into that LAN.

| Tool | Use |
| --- | --- |
| `get_snapshot` | Capture a JPEG and return image content to the agent |
| `get_status` | Read camera and device status |
| `set_camera_facing` | Select front or rear camera |
| `set_camera_config` | Change image settings; parameters differ by version |
| `warmup_camera` | Pre-open the camera; 8.0+ only |

For example, ask: “Take a photo and describe what you see,” or “Switch to the rear camera and take another photo.”

The 8.0+ implementation reports MCP revision `2024-11-05` over JSON-RPC HTTP POST; Legacy supports revisions `2025-06-18`, `2025-11-25`, and `2026-07-28`. Client compatibility depends on the revision and transport it accepts. The advertised `set_camera_enabled` tool rejects remote calls in both versions; enable camera access on the phone instead.

To inspect the tool catalogue on the 8.0+ version:

```bash
curl http://PHONE_IP:8080/mcp \
  --header 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Legacy clients should negotiate a supported MCP revision before listing tools. Use the returned schemas rather than assuming both versions accept the same camera settings.

## Home Assistant

### Video through MJPEG IP Camera

Add the [MJPEG IP Camera integration](https://www.home-assistant.io/integrations/mjpeg/) under **Settings → Devices & services → Add integration**. Enter:

| Field | Value |
| --- | --- |
| MJPEG URL | `http://PHONE_IP:8080/video.mjpeg` |
| Still image URL | `http://PHONE_IP:8080/snapshot.jpg` |
| Username / password | The app's HTTP credentials, if enabled |

Include your path prefix in both URLs if you configured one. A persistent live view or frequent thumbnail refreshes will keep activating the camera. Use occasional snapshots when you want it to spend more time idle.

### Entities and controls through MQTT

Configure Home Assistant's MQTT integration and the phone to use the same broker. In the app's MQTT settings, enter the broker host, port, credentials, and topic prefix, then enable MQTT. In the 8.0+ version, give each phone a unique client ID. Legacy generates its own device ID.

The app publishes discovery messages under `homeassistant/`. Home Assistant can then show device status and controls for HTTP, MCP, camera mode, rotation, and screen brightness. Sensor availability differs by version. MQTT adds these entities; video still comes from the HTTP URLs above.

For custom automations, the topic layouts differ:

| | Android 8.0+ | Legacy |
| --- | --- | --- |
| Status | `<prefix>/<clientId>/status` | `<prefix>/<deviceId>/state` |
| Commands | `<prefix>/<clientId>/set` | `<prefix>/<deviceId>/camera/set` |
| Command result | `<prefix>/<clientId>/set/result` | No separate result topic |

Both accept this command format:

```json
{"http_enabled":true,"mcp_enabled":true,"camera_mode":"back","rotation":90,"screen_brightness":50}
```

Send only the fields you want to change. Camera mode is `back`, `front`, or `disabled`; rotation is `0`, `90`, `180`, or `270`; brightness is `0`–`100` and affects the app window, without waking the screen. Use non-retained commands to avoid replaying old actions. The 8.0+ version ignores retained commands and reports `success` or `error` on its result topic. Combined commands do not provide transaction rollback.

## Settings and troubleshooting

| Setting | What it changes |
| --- | --- |
| Lens / disable front camera | Select a camera or prevent front-camera use |
| Resolution, FPS, JPEG quality | Balance detail, bandwidth, and encoding load |
| Camera keep-alive | Trade quicker repeat captures for more time with the camera open |
| Screen wake duration / dimmed brightness | Control the app's idle screen behavior |
| Ignore battery optimization | Help the service remain available with the screen off |
| Bind IP, port, path prefix | Choose where HTTP and MCP listen |
| HTTP basic authentication | Protect image, control, and MCP requests with credentials |
| Language / theme | Choose the interface language and appearance |

Background and screen-off operation depends on the phone's power-management settings. Allow background activity or autostart where your device requires it, keep the background service enabled, and avoid force-stopping the app.

**Cannot connect:** check the phone's current address, port conflicts, Wi-Fi client isolation, and whether the relevant service is enabled. **404:** check the path prefix and HTTP/MCP toggle. **401:** check credentials. **Placeholder image:** check the local camera access setting. **Stops after locking:** check battery optimization and the manufacturer's background restrictions. **Too much heat or bandwidth:** lower resolution, FPS, or JPEG quality, and check for clients holding streams open.

Basic authentication does not encrypt HTTP traffic. Use a trusted LAN or a VPN for remote access. The 8.0+ `/health` route does not require authentication. No cloud account is needed for direct HTTP/MCP access; MQTT needs a broker you configure.

## Comparison

[DroidCam Classic](https://www.dev47apps.com/) focuses on using the phone as a PC webcam, with a client that installs video and audio drivers. Its [browser/MJPEG mode](https://www.dev47apps.com/droidcam/connect/) also works without that client.

[Android IP Camera by DigitallyRefined](https://github.com/DigitallyRefined/android-ip-camera) supports H.264/MJPEG, a web control panel, HTTPS, and recording. It also supports background and screen-off streaming, and connects to Home Assistant through MJPEG.

Camera Agent focuses on camera requests from people, MCP agents, and MQTT automations, with camera release after idle time. Its video output is JPEG/MJPEG; it does not provide a PC webcam driver or audio streaming. Background operation is shared with the other apps, so it is not an exclusive advantage.

## Build and release

Use JDK 17 and Android SDK platform 36 with Build Tools 35.0.0. Run from the repository root:

```bash
(cd android-8.0-camera-agent && bash ./gradlew testDebugUnitTest assembleDebug)
(cd android-4.0-camera-agent/android && bash ./gradlew testDebugUnitTest assembleDebug)
```

Each APK is written to its project's `app/build/outputs/apk/debug/`. Modern code is Kotlin/Camera2; Legacy is Java/Camera1 with no runtime dependencies.

For maintainers, the [release workflow](.github/workflows/release.yml) tests and builds both versions when a `v1.0.0` or `v1.0.0-beta.1` tag is pushed. Configure these GitHub Actions secrets first:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Release keystore encoded as Base64; on Linux: `base64 -w 0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the signing key and backups outside the repository. Updates must use the same signing key. The two apps have different application IDs and may share a signing key. Plan a separate upload key when adopting Google Play App Signing; the current SDK targets also need review before a Play release.

```bash
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

The workflow publishes signed APKs and `SHA256SUMS.txt` to GitHub Releases; tags with suffixes become prereleases. `versionName` comes from the tag without `v`, and `versionCode` is the workflow run number plus 100. Preserve the workflow name and run history to keep version codes increasing. Local default versions stay unchanged; missing signing secrets fail the workflow.
