#!/usr/bin/env bash
set -euo pipefail

echo "[android-install] ERROR: el cifrador Android ya no se distribuye como APK instalable." >&2
echo "[android-install] Compile el AAR con ./scripts/android/compilacion/build-cifrador.sh y consúmalo desde otra app Android." >&2
exit 1
