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
