#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"
REBUILD_IMAGE="${REBUILD_IMAGE:-1}"
IMAGE_ROOT="$PROJECT_ROOT/dist/android/image/$APP_NAME"
OUTPUT_DIR="$PROJECT_ROOT/dist/android"
ARCHIVE_PATH="$OUTPUT_DIR/$APP_NAME-$APP_VERSION-android-portable.zip"

cd "$PROJECT_ROOT"

if [[ "$REBUILD_IMAGE" == "1" || ! -f "$IMAGE_ROOT/aar/$APP_NAME-android-debug.aar" ]]; then
  "$PROJECT_ROOT/scripts/android/empaquetado/build-cifrador-portable.sh"
fi

mkdir -p "$OUTPUT_DIR"
rm -f "$ARCHIVE_PATH"

(
  cd "$PROJECT_ROOT/dist/android/image"
  zip -qr "$ARCHIVE_PATH" "$APP_NAME"
)

echo "[android-portable] Paquete generado en: $ARCHIVE_PATH"
