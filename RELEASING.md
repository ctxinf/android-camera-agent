# Source builds and releases

## Build both apps

Use OpenJDK 17, Android SDK platform 36, Build Tools 35.0.0 and the checked-in
Gradle 8.14.3 wrapper. Dependencies come from Google Maven and Maven Central.
No API keys, proprietary services or signing secrets are required to build.

```sh
python3 tools/check_release.py
python3 tools/check_i18n.py
(cd android-8.0-camera-agent && bash ./gradlew testReleaseUnitTest assembleRelease)
(cd android-4.0-camera-agent/android && bash ./gradlew testReleaseUnitTest assembleRelease)
```

Unsigned APKs are written to each project's `app/build/outputs/apk/release/`.
Signing is optional and only enabled when `ANDROID_KEYSTORE_PATH` is set; the
associated environment variables are `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. F-Droid signs its own builds.

## Version, settings ref and Git tag

`version.properties` is the single source for both application IDs:
`io.github.ctxinf.cameraagent` and `io.github.ctxinf.cameraagent.legacy`.
The next release is version **1.0.5**, versionCode **10005**, tag **v1.0.5**.
The release settings footer shows `Version 1.0.5` and `ref: v1.0.5`.
Debug builds show version `1.0.5-dev` and ref `v1.0.5-dev`; this ref identifies
the development version, not an existing Git tag or an exact commit.

For each release:

1. Update `versionName` and increase `versionCode` in `version.properties`.
   Version codes are explicit integers shared across distribution channels;
   never derive them from CI run numbers. Never reuse or lower a published code.
2. Update both `fdroid/*.yml` recipes (Builds and CurrentVersion fields) and add
   localized `fastlane/metadata/android/*/changelogs/<versionCode>.txt` files.
3. Run the checks and both builds above; inspect the settings footer on a device.
4. Commit the complete source, then tag that commit with `v<versionName>` and
   push the commit and tag. Do not move existing release tags.
   The GitHub release workflow checks that the tag matches the source version
   before building, signing and publishing both APKs.

Versions are deliberately not overridden with `-PreleaseVersionName` or
`-PreleaseVersionCode`: a source checkout must produce the same app version
on GitHub, locally and on F-Droid.

## F-Droid submission

`fdroid/*.yml` contains proposed **fdroiddata** metadata, one file per app ID.
These files do not publish the app by themselves. The referenced `v1.0.5` tag
must be created from the finished release commit and publicly reachable first.
Copy the recipes into an fdroiddata checkout's `metadata/` directory, then run:

```sh
fdroid rewritemeta io.github.ctxinf.cameraagent io.github.ctxinf.cameraagent.legacy
fdroid lint io.github.ctxinf.cameraagent io.github.ctxinf.cameraagent.legacy
fdroid checkupdates io.github.ctxinf.cameraagent io.github.ctxinf.cameraagent.legacy
fdroid build --verbose io.github.ctxinf.cameraagent:10005
fdroid build --verbose io.github.ctxinf.cameraagent.legacy:10005
```

The recipes use the root version file for tag update discovery and ordinary
unsigned Gradle release builds. The fastlane descriptions, changelogs and
screenshots live in `fastlane/metadata/android/`; both apps share product copy.
The recipes set their own app names to distinguish the legacy package.
The legacy description can be adapted by the fdroiddata maintainer as needed.

The project is Apache-2.0, including original artwork; third-party notices are
retained. Runtime dependencies are FLOSS libraries from trusted Maven sources.
No advertising, tracking, Play Services, Firebase or runtime code downloads
were found in the current application source/dependency declarations.

Remaining external release gates:

- F-Droid's current inclusion policy normally requires at least three months of
  active development, with exceptions such as proven existing users. The first
  repository commit is dated 2026-10-17. Supply evidence for an applicable
  exception or wait until the development-history requirement is met; the
  maintainers make the inclusion decision.
- Run the recipes with fdroidserver and its scanner/build environment. Local
  Gradle success is not proof that F-Droid's scanner or build infrastructure passes.
- Submit an inclusion request / fdroiddata merge request after the public source
  and matching release tag are available. This preparation does not submit one.
- Default F-Droid signing differs from GitHub signing, so users may need to
  uninstall before switching channels. Preserve GitHub signing keys for updates.
  Cross-channel signing requires a separate reproducible-build setup; it is not
  claimed by these recipes.

The low target SDKs (26 modern, 18 legacy) preserve existing background-camera
behavior. A future Google Play release needs a separate target-SDK migration,
including foreground-camera and boot behavior, permission handling, current
store policies and device tests. Do not increase targets solely for metadata
without that work. Keep package IDs and version codes stable for future stores.

References: [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/),
[metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/),
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).
