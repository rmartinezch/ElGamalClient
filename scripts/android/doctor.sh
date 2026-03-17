#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
source "$PROJECT_ROOT/scripts/android/lib-android-env.sh"
ANDROID_SDK_ROOT="$(resolve_android_sdk_root "$PROJECT_ROOT")"
NDK_VERSION="27.2.12479018"

echo "[android-doctor] Proyecto: $ANDROID_DIR"

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "[android-doctor] ERROR: no se encontró gradle wrapper en android/." >&2
  exit 1
fi

echo "[android-doctor] JAVA_HOME=${JAVA_HOME:-<no definido>}"
java -version >/dev/null 2>&1 || {
  echo "[android-doctor] ERROR: java no está disponible en PATH." >&2
  exit 1
}

if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "[android-doctor] ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
else
  echo "[android-doctor] WARN: ANDROID_SDK_ROOT no está definido."
fi

if [[ -f "$ANDROID_DIR/local.properties" ]]; then
  echo "[android-doctor] local.properties encontrado."
else
  echo "[android-doctor] WARN: falta android/local.properties (usar local.properties.example)."
fi

ADB_BIN="$(resolve_adb_bin "$PROJECT_ROOT")"
if command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "[android-doctor] adb disponible: $ADB_BIN"
  "$ADB_BIN" version | head -n 1
else
  echo "[android-doctor] WARN: adb no esta disponible ni en PATH ni en ANDROID_SDK_ROOT."
fi

if [[ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "[android-doctor] sdkmanager disponible."
else
  echo "[android-doctor] WARN: falta cmdline-tools/latest/bin/sdkmanager."
fi

if [[ -x "$ANDROID_SDK_ROOT/emulator/emulator" ]]; then
  echo "[android-doctor] emulator disponible."
else
  echo "[android-doctor] WARN: falta Android Emulator."
fi

if [[ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]]; then
  echo "[android-doctor] NDK $NDK_VERSION disponible."
else
  echo "[android-doctor] WARN: falta NDK $NDK_VERSION."
fi

if [[ -d "$ANDROID_SDK_ROOT/system-images/android-34/google_apis/x86_64" ]]; then
  echo "[android-doctor] system-image x86_64 disponible."
else
  echo "[android-doctor] WARN: falta system-image android-34/google_apis/x86_64."
fi

echo "[android-doctor] OK: chequeo base completado."
