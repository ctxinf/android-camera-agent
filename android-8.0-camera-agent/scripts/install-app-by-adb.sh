#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

while [ ! -f "$PROJECT_DIR/gradlew" ]; do
    PARENT_DIR="$(dirname "$PROJECT_DIR")"
    if [ "$PARENT_DIR" = "$PROJECT_DIR" ]; then
        echo "Error: Cannot locate project root containing gradlew." >&2
        exit 1
    fi
    PROJECT_DIR="$PARENT_DIR"
done

# Use Android Studio JBR if JAVA_HOME is not set or not executable
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    if [ -d "${HOME}/.local/share/android-studio/jbr" ]; then
        export JAVA_HOME="${HOME}/.local/share/android-studio/jbr"
    fi
fi

if ! command -v adb >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        export PATH="$ANDROID_HOME/platform-tools:$PATH"
    else
        echo "Error: adb command not found in PATH." >&2
        exit 1
    fi
fi

DEVICE_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if [ -z "$DEVICE_SERIAL" ]; then
    echo "Error: No active adb device found." >&2
    echo "Current device list:" >&2
    adb devices -l >&2
    exit 1
fi

echo "Target device: $DEVICE_SERIAL"

echo "Building debug APK..."
(
    cd "$PROJECT_DIR"
    ./gradlew assembleDebug
)

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH" >&2
    exit 1
fi

echo "Pushing $APK_PATH to device temporary storage..."
TMP_APK="/data/local/tmp/camera-agent-app.apk"
adb -s "$DEVICE_SERIAL" push "$APK_PATH" "$TMP_APK"

echo "Installing on $DEVICE_SERIAL via pm install..."
adb -s "$DEVICE_SERIAL" shell pm install -r -d -g "$TMP_APK"
adb -s "$DEVICE_SERIAL" shell rm -f "$TMP_APK"

echo "Successfully installed on device: $DEVICE_SERIAL"
