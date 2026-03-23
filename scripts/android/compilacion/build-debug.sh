#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"
ANDROID_DIR="$(resolve_android_project_dir "$PROJECT_ROOT")"
PREBUILT_ANDROID_AAR_DIR="$PROJECT_ROOT/prebuilt/android/aar"

sync_prebuilt_outputs() {
  local aar_source
  aar_source="$ANDROID_DIR/app/build/outputs/aar/app-debug.aar"

  mkdir -p "$PREBUILT_ANDROID_AAR_DIR"

  if [[ -f "$aar_source" ]]; then
    cp -f "$aar_source" "$PREBUILT_ANDROID_AAR_DIR/ElGamalCipher-android-debug.aar"
  fi
}

if [[ "${SKIP_ANDROID_JNI:-0}" != "1" ]]; then
  "$PROJECT_ROOT/scripts/android/compilacion/build-jni.sh"
fi

cd "$ANDROID_DIR"

./gradlew :app:assembleDebug
sync_prebuilt_outputs

printf '[android-build] AAR exportado en: %s\n' "$PREBUILT_ANDROID_AAR_DIR/ElGamalCipher-android-debug.aar"
