#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# 从脚本目录逐级向上寻找项目根目录，因此脚本位于根目录或 scripts/
# 中都可以，也不受当前工作目录影响。
PROJECT_DIR=$SCRIPT_DIR
while [ ! -f "$PROJECT_DIR/android/app/build.gradle" ]; do
    PARENT_DIR=$(dirname -- "$PROJECT_DIR")
    if [ "$PARENT_DIR" = "$PROJECT_DIR" ]; then
        echo "错误：无法从脚本位置找到项目根目录。" >&2
        exit 1
    fi
    PROJECT_DIR=$PARENT_DIR
done

ANDROID_DIR="$PROJECT_DIR/android"
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
BUILD_STAMP="$ANDROID_DIR/app/build/outputs/apk/debug/.last-successful-build"

# APK 不存在，或源码、资源、Manifest、构建配置在上次成功构建后有更新时才构建。
NEEDS_BUILD=false
if [ ! -f "$APK_PATH" ] || [ ! -f "$BUILD_STAMP" ]; then
    NEEDS_BUILD=true
elif find "$ANDROID_DIR/app/src" -type f -newer "$BUILD_STAMP" -print -quit \
        | grep -q .; then
    NEEDS_BUILD=true
else
    for BUILD_INPUT in \
        "$ANDROID_DIR/build.gradle" \
        "$ANDROID_DIR/settings.gradle" \
        "$ANDROID_DIR/gradle.properties" \
        "$ANDROID_DIR/app/build.gradle" \
        "$ANDROID_DIR/gradle/wrapper/gradle-wrapper.properties"
    do
        if [ -f "$BUILD_INPUT" ] && [ "$BUILD_INPUT" -nt "$BUILD_STAMP" ]; then
            NEEDS_BUILD=true
            break
        fi
    done
fi

if [ "$NEEDS_BUILD" = true ]; then
    if [ -x "$ANDROID_DIR/gradlew" ]; then
        echo "源码有更新，使用 Gradle Wrapper 构建 Debug APK..."
        (
            cd "$ANDROID_DIR"
            ./gradlew --no-daemon :app:assembleDebug
        )
    elif command -v gradle >/dev/null 2>&1; then
        echo "源码有更新，使用系统 Gradle 构建 Debug APK..."
        (
            cd "$ANDROID_DIR"
            gradle --no-daemon :app:assembleDebug
        )
    else
        echo "错误：APK 需要重新构建，但找不到 Gradle Wrapper 或系统 gradle。" >&2
        exit 1
    fi
    touch "$BUILD_STAMP"
else
    echo "APK 已是最新，跳过构建。"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "错误：构建完成后未找到 APK：$APK_PATH" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "错误：找不到 adb，请先安装 Android Platform Tools 并加入 PATH。" >&2
    exit 1
fi

DEVICE_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')

if [ -z "$DEVICE_SERIAL" ]; then
    echo "错误：没有找到状态为 device 的 ADB 设备。" >&2
    echo "请检查 USB 调试授权；当前设备列表：" >&2
    adb devices -l >&2
    exit 1
fi

echo "使用第一个 ADB 设备：$DEVICE_SERIAL"
echo "安装 APK：$APK_PATH"
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"
