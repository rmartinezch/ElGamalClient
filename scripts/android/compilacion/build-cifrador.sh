#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${SKIP_ANDROID_JNI:-0}" != "1" ]]; then
  "$SCRIPT_DIR/build-jni.sh"
fi

SKIP_ANDROID_JNI=1 "$SCRIPT_DIR/build-debug.sh"

echo "[android-build] Cifrador Android compilado: JNI + AAR."
