#!/bin/bash
set -e

PROJECT_DIR="/Users/wyy/Desktop/450/CarPlaySiQiRelay"
cd "$PROJECT_DIR"

export JAVA_HOME="${JAVA_HOME:-/Users/wyy/Library/Java/JavaVirtualMachines/temurin-24/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/wyy/Library/Android/sdk}"

echo "=== Building CarPlaySiQiRelay Integrated APK (with DiPlay + KOVE OEM Badge) ==="
./gradlew :app:assembleDebug --stacktrace

OUT_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TARGET_APK="$PROJECT_DIR/CarPlaySiQiRelay_debug.apk"
if [ ! -f "$OUT_APK" ]; then
    echo "ERROR: Expected APK not found at $OUT_APK" >&2
    exit 1
fi
cp "$OUT_APK" "$TARGET_APK"
echo "=== Build Succeeded! Output APK: $TARGET_APK ==="
