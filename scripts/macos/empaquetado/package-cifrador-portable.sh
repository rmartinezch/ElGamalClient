#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
DIST_DIR="$PROJECT_ROOT/dist/macos"
IMAGE_DIR="$DIST_DIR/image/$APP_NAME"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$DIST_DIR/${APP_NAME}-macos-portable-${TIMESTAMP}.tar.gz"

"$PROJECT_ROOT/scripts/macos/empaquetado/build-cifrador-portable.sh"

mkdir -p "$DIST_DIR"
tar -C "$DIST_DIR/image" -czf "$ARCHIVE" "$APP_NAME"

printf '[macos-portable] Paquete generado en: %s\n' "$ARCHIVE"
