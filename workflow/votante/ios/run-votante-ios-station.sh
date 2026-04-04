#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SOURCE_DIR="$PROJECT_ROOT/workflow/votante/windows/app/src"
CLASSES_DIR="$PROJECT_ROOT/.build/votante-ios/classes"
MAIN_CLASS="pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"

PORT="${PORT:-8798}"
BIND_HOST="${BIND_HOST:-0.0.0.0}"
PUBLIC_HOST="${PUBLIC_HOST:-}"
SERVICE_BASE_URL="${SERVICE_BASE_URL:-}"
AUXSID="${AUXSID:-}"
APP_VERSION="${APP_VERSION:-1.1.0}"
OPEN_SIMULATOR="${OPEN_SIMULATOR:-0}"

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

mkdir -p "$CLASSES_DIR"
find "$SOURCE_DIR" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$CLASSES_DIR"

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
JAVA_ARGS=(
  "-Dvotante.root=$PROJECT_ROOT"
  "-Dvotante.station.id=ios"
  "-Dvotante.station.bindHost=$BIND_HOST"
  "-Dvotante.station.port=$PORT"
  "-Dvotante.station.publicDir=$PROJECT_ROOT/workflow/votante/windows/app/public"
  "-Dvotante.station.cifradorDir=$PROJECT_ROOT/dist/ios/library/Cifrador"
  "-Dvotante.station.cifradorJarName=ElGamalCipher-${APP_VERSION}.jar"
  "-Dvotante.station.cifradorNativeSubdir=$NATIVE_CLASSIFIER"
  "-Dvotante.station.javaBinName=java"
  "-Dvotante.station.interfaceDisplayName=Estacion de votacion iOS"
  "-Dvotante.station.cipherDisplayName=Cifrador iOS (bridge macOS)"
  "-Dvotante.station.cipherRngSupport=Software y hardware (bridge host macOS)"
)

if [[ -n "$PUBLIC_HOST" ]]; then
  JAVA_ARGS+=("-Dvotante.station.publicHost=$PUBLIC_HOST")
fi
if [[ -n "$SERVICE_BASE_URL" ]]; then
  JAVA_ARGS+=("-Dvotante.station.serviceBaseUrl=$SERVICE_BASE_URL")
fi
if [[ -n "$AUXSID" ]]; then
  JAVA_ARGS+=("-Dvotante.station.auxsid=$AUXSID")
fi

if [[ "$OPEN_SIMULATOR" == "1" ]]; then
  if command -v xcrun >/dev/null 2>&1; then
    xcrun simctl openurl booted "http://127.0.0.1:${PORT}" >/dev/null 2>&1 || true
  fi
fi

java "${JAVA_ARGS[@]}" -cp "$CLASSES_DIR" "$MAIN_CLASS"
