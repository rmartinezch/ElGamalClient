#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
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

JAR_PATH="$PROJECT_ROOT/prebuilt/java/ElGamalCipher-${APP_VERSION}.jar"
NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
NATIVE_DIR="$PROJECT_ROOT/prebuilt/$NATIVE_CLASSIFIER"

if [[ ! -f "$JAR_PATH" || ! -f "$NATIVE_DIR/libvecj-2.2.0.dylib" || ! -f "$NATIVE_DIR/libvmgj-1.3.0.dylib" ]]; then
  "$PROJECT_ROOT/scripts/macos/compilacion/build-cifrador.sh"
fi

if [[ $# -lt 3 ]]; then
  echo "Uso: $0 <publicKey> <plain_votes.txt> <ciphertexts_ext> [args-extra]" >&2
  exit 1
fi

export DYLD_LIBRARY_PATH="$NATIVE_DIR:${DYLD_LIBRARY_PATH:-}"
exec java -Djava.library.path="$NATIVE_DIR" -jar "$JAR_PATH" "$@"
