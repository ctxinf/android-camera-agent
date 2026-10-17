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

Both editions provide a master service switch and independent HTTP, MCP and MQTT
checkboxes on the homepage and in settings. HTTP defaults to enabled; MCP and
MQTT default to disabled. The master switch pauses network services without
clearing the individual choices. Camera access remains a separate camera setting;
the camera indicator reports actual hardware use, including preview and warmup.

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
