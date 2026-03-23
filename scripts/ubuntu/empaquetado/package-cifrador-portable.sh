#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"
REBUILD_IMAGE="${REBUILD_IMAGE:-1}"

cd "$PROJECT_ROOT"

IMAGE_ROOT="$PROJECT_ROOT/dist/linux/image/${APP_NAME}"
OUTPUT_DIR="$PROJECT_ROOT/dist/linux"
ARCH_RAW="$(uname -m)"
ARCH_LABEL="$ARCH_RAW"
case "$ARCH_RAW" in
  x86_64|amd64) ARCH_LABEL="x64" ;;
  aarch64|arm64) ARCH_LABEL="arm64" ;;
esac

if [[ "$REBUILD_IMAGE" == "1" || ! -x "$IMAGE_ROOT/$APP_NAME" ]]; then
  scripts/ubuntu/empaquetado/build-cifrador-portable.sh
fi

mkdir -p "$OUTPUT_DIR"
ARCHIVE_PATH="$OUTPUT_DIR/${APP_NAME}-${APP_VERSION}-linux-${ARCH_LABEL}-portable.tar.gz"
rm -f "$ARCHIVE_PATH"

tar -C "$PROJECT_ROOT/dist/linux/image" -czf "$ARCHIVE_PATH" "$APP_NAME"

echo "[ubuntu-portable] Paquete generado en: $ARCHIVE_PATH"
