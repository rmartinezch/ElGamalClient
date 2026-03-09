#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${SKIP_ANDROID_JNI:-0}" != "1" ]]; then
  "$PROJECT_ROOT/scripts/android/build-jni.sh"
fi
cd "$PROJECT_ROOT/android"

./gradlew :app:assembleDebug
