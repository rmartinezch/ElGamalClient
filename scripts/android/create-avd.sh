#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULT_SDK_ROOT="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$DEFAULT_SDK_ROOT}"
AVD_NAME="${ANDROID_AVD_NAME:-CifradorApi34}"
SYSTEM_IMAGE="${ANDROID_SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"

if [[ -f "$ANDROID_DIR/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk.dir=//p' "$ANDROID_DIR/local.properties" | tail -n 1)"
  if [[ -n "$sdk_dir" ]]; then
    ANDROID_SDK_ROOT="$sdk_dir"
  fi
fi

AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
if [[ ! -x "$AVDMANAGER" ]]; then
  echo "[create-avd] ERROR: falta avdmanager en $AVDMANAGER" >&2
  exit 1
fi

if "$AVDMANAGER" list avd | grep -q "Name: $AVD_NAME"; then
  echo "[create-avd] AVD ya existe: $AVD_NAME"
  exit 0
fi

mkdir -p "$HOME/.android/avd"
echo "no" | "$AVDMANAGER" create avd \
  --force \
  --name "$AVD_NAME" \
  --package "$SYSTEM_IMAGE" \
  --abi "google_apis/x86_64" \
  --device "pixel_5"

echo "[create-avd] OK: AVD creado: $AVD_NAME"
