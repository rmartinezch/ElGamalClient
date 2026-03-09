#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEFAULT_SDK_ROOT="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$DEFAULT_SDK_ROOT}"
AVD_NAME="${ANDROID_AVD_NAME:-CifradorApi34}"
EMULATOR_PORT="${ANDROID_EMULATOR_PORT:-5556}"
EMULATOR_SERIAL="emulator-$EMULATOR_PORT"
LOG_DIR="$PROJECT_ROOT/.build/android"
LOG_FILE="$LOG_DIR/emulator-$EMULATOR_PORT.log"
PID_FILE="$LOG_DIR/emulator-$EMULATOR_PORT.pid"
ACCEL_MODE="${ANDROID_EMULATOR_ACCEL:-auto}"

"$PROJECT_ROOT/scripts/android/create-avd.sh"

mkdir -p "$LOG_DIR"
EMULATOR_BIN="$ANDROID_SDK_ROOT/emulator/emulator"
ADB_BIN="${ADB:-adb}"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "[start-emulator] ERROR: falta emulator en $EMULATOR_BIN" >&2
  exit 1
fi

if "$ADB_BIN" devices | awk 'NR>1 {print $1}' | grep -qx "$EMULATOR_SERIAL"; then
  echo "[start-emulator] Emulador ya activo: $EMULATOR_SERIAL"
else
  nohup "$EMULATOR_BIN" \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-snapshot \
    -no-boot-anim \
    -no-audio \
    -no-window \
    -gpu swiftshader_indirect \
    -accel "$ACCEL_MODE" \
    >"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
fi

"$ADB_BIN" -s "$EMULATOR_SERIAL" wait-for-device

for _ in $(seq 1 120); do
  if [[ "$("$ADB_BIN" -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    "$ADB_BIN" -s "$EMULATOR_SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
    echo "[start-emulator] OK: emulador listo: $EMULATOR_SERIAL"
    exit 0
  fi
  sleep 5
done

echo "[start-emulator] ERROR: timeout esperando el arranque del emulador." >&2
exit 1
