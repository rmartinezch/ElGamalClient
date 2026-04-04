#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SOURCE_DIR="$PROJECT_ROOT/workflow/votante/windows/app/src"
CLASSES_DIR="$PROJECT_ROOT/.build/votante-macos/classes"
MAIN_CLASS="pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"

PORT="${PORT:-8789}"
BIND_HOST="${BIND_HOST:-0.0.0.0}"
PUBLIC_HOST="${PUBLIC_HOST:-}"
SERVICE_BASE_URL="${SERVICE_BASE_URL:-}"
AUXSID="${AUXSID:-}"
APP_VERSION="${APP_VERSION:-1.1.0}"

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

if [[ ! -f "$PROJECT_ROOT/prebuilt/java/ElGamalCipher-${APP_VERSION}.jar" ]]; then
  "$PROJECT_ROOT/scripts/macos/compilacion/build-cifrador.sh"
fi

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
if [[ ! -f "$PROJECT_ROOT/prebuilt/$NATIVE_CLASSIFIER/libvecj-2.2.0.dylib" || ! -f "$PROJECT_ROOT/prebuilt/$NATIVE_CLASSIFIER/libvmgj-1.3.0.dylib" ]]; then
  "$PROJECT_ROOT/scripts/macos/compilacion/build-native-macos.sh"
fi

mkdir -p "$CLASSES_DIR"
find "$SOURCE_DIR" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$CLASSES_DIR"

JAVA_ARGS=(
  "-Dvotante.root=$PROJECT_ROOT"
  "-Dvotante.station.id=macos"
  "-Dvotante.station.bindHost=$BIND_HOST"
  "-Dvotante.station.port=$PORT"
  "-Dvotante.station.publicDir=$PROJECT_ROOT/workflow/votante/windows/app/public"
  "-Dvotante.station.cifradorDir=$PROJECT_ROOT/dist/macos/image/Cifrador"
  "-Dvotante.station.cifradorJarName=ElGamalCipher-${APP_VERSION}.jar"
  "-Dvotante.station.cifradorNativeSubdir=$NATIVE_CLASSIFIER"
  "-Dvotante.station.javaBinName=java"
  "-Dvotante.station.interfaceDisplayName=Estacion de votacion macOS"
  "-Dvotante.station.cipherDisplayName=Cifrador macOS"
  "-Dvotante.station.cipherRngSupport=Software y hardware (TrueRNG USB/COM)"
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

java "${JAVA_ARGS[@]}" -cp "$CLASSES_DIR" "$MAIN_CLASS"
