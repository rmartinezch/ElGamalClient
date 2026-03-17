#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
ANDROID_DIR="$ROOT_DIR/android"
OUTPUT_DIR="$ROOT_DIR/dist/android"
APK_SOURCE="$ANDROID_DIR/truerngdiag/build/outputs/apk/debug/truerngdiag-debug.apk"
APK_TARGET="$OUTPUT_DIR/TrueRNG-Diagnostico.apk"

mkdir -p "$OUTPUT_DIR"
(
  cd "$ANDROID_DIR"
  ./gradlew :truerngdiag:assembleDebug
)
cp "$APK_SOURCE" "$APK_TARGET"

echo "APK generado en: $APK_TARGET"
