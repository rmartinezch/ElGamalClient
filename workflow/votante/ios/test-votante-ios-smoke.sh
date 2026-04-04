#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CLASSES_DIR="$PROJECT_ROOT/.build/votante-ios/classes"
SOURCE_DIR="$PROJECT_ROOT/workflow/votante/windows/app/src"
MAIN_CLASS="pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterSmokeTest"
APP_VERSION="${APP_VERSION:-1.1.0}"
PORT="${SMOKE_PORT:-8791}"
MOCK_PORT="${MOCK_PORT:-7041}"

# Asegurar herramientas locales (.tools/) en el PATH
TOOLS_DIR="$PROJECT_ROOT/.tools"
if [[ -d "$TOOLS_DIR/jdk-21/bin" ]]; then
  export JAVA_HOME="$TOOLS_DIR/jdk-21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

resolve_macos_classifier() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "macos-x64" ;;
    arm64|aarch64) echo "macos-arm64" ;;
    *) echo "macos-${arch}" ;;
  esac
}

"$PROJECT_ROOT/scripts/ios/compilacion/build-cifrador.sh"

python3 "$PROJECT_ROOT/workflow/votante/macos/mock_mixer_server.py" --host 127.0.0.1 --port "$MOCK_PORT" &
MOCK_PID=$!
trap 'kill "$MOCK_PID" >/dev/null 2>&1 || true' EXIT

sleep 1

mkdir -p "$CLASSES_DIR"
find "$SOURCE_DIR" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$CLASSES_DIR"

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
OUTPUT=$(java \
  -Dvotante.root="$PROJECT_ROOT" \
  -Dvotante.station.id=ios \
  -Dvotante.station.bindHost=127.0.0.1 \
  -Dvotante.station.publicHost=127.0.0.1 \
  -Dvotante.station.smokePort="$PORT" \
  -Dvotante.station.serviceBaseUrl="http://127.0.0.1:$MOCK_PORT" \
  -Dvotante.station.publicDir="$PROJECT_ROOT/workflow/votante/windows/app/public" \
  -Dvotante.station.cifradorDir="$PROJECT_ROOT/dist/ios/library/Cifrador" \
  -Dvotante.station.cifradorJarName="ElGamalCipher-${APP_VERSION}.jar" \
  -Dvotante.station.cifradorNativeSubdir="$NATIVE_CLASSIFIER" \
  -Dvotante.station.javaBinName=java \
  -cp "$CLASSES_DIR" "$MAIN_CLASS")

echo "$OUTPUT" | grep 'receiptAccepted.*true' >/dev/null
printf '[votante-ios-smoke] OK: flujo iOS (bridge host macOS) validado.\n'
