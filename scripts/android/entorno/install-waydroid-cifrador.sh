#!/usr/bin/env bash
set -euo pipefail

echo "[waydroid-cifrador] ERROR: el cifrador Android ya no se distribuye como APK instalable." >&2
echo "[waydroid-cifrador] Use el AAR exportado en prebuilt/android/aar o el bundle de dist/android/image/Cifrador." >&2
exit 1
