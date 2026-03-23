#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"
ANDROID_DIR="$(resolve_android_project_dir "$PROJECT_ROOT")"
EMULATOR_PORT="${ANDROID_EMULATOR_PORT:-5556}"
export ANDROID_SERIAL="emulator-$EMULATOR_PORT"
ADB_BIN="$(resolve_adb_bin "$PROJECT_ROOT")"

cleanup() {
  "$ADB_BIN" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$PROJECT_ROOT/scripts/android/compilacion/build-debug.sh"
"$PROJECT_ROOT/scripts/android/entorno/start-emulator.sh"

cd "$ANDROID_DIR"
./gradlew :app:connectedDebugAndroidTest
