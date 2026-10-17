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

echo "=========================================="
echo " Building Release APK..."
echo " Project root: $PROJECT_DIR"
echo "=========================================="

(
    cd "$PROJECT_DIR"
    ./gradlew assembleRelease "$@"
)

RELEASE_DIR="$PROJECT_DIR/app/build/outputs/apk/release"

if [ ! -d "$RELEASE_DIR" ]; then
    echo "Error: Release output directory not found at $RELEASE_DIR" >&2
    exit 1
fi

APKS=()
while IFS= read -r -d '' file; do
    APKS+=("$file")
done < <(find "$RELEASE_DIR" -maxdepth 1 -type f -name "*.apk" -print0)

if [ ${#APKS[@]} -eq 0 ]; then
    echo "Error: No release APK found in $RELEASE_DIR" >&2
    exit 1
fi

echo ""
echo "=========================================="
echo " Release APK Build Finished Successfully"
echo "=========================================="

METADATA_FILE="$RELEASE_DIR/output-metadata.json"
if [ -f "$METADATA_FILE" ]; then
    echo "Metadata file: $METADATA_FILE"
fi

for apk in "${APKS[@]}"; do
    FILE_NAME="$(basename "$apk")"
    FILE_SIZE_HUMAN="$(du -h "$apk" | awk '{print $1}')"
    FILE_SIZE_BYTES="$(stat -c%s "$apk" 2>/dev/null || wc -c < "$apk")"
    SHA256="$(sha256sum "$apk" | awk '{print $1}')"
    MD5="$(md5sum "$apk" | awk '{print $1}')"

    echo ""
    echo "APK Name:       $FILE_NAME"
    echo "APK Path:       $apk"
    echo "File Size:      $FILE_SIZE_HUMAN ($FILE_SIZE_BYTES bytes)"
    echo "SHA-256:        $SHA256"
    echo "MD5:            $MD5"
done

echo "=========================================="
