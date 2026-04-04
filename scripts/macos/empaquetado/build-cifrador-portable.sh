#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"

resolve_macos_classifier() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "macos-x64" ;;
    arm64|aarch64) echo "macos-arm64" ;;
    *) echo "macos-${arch}" ;;
  esac
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi

  local java_bin
  java_bin="$(command -v java || true)"
  if [[ -z "$java_bin" ]]; then
    echo "No se encontro java en PATH ni JAVA_HOME." >&2
    exit 1
  fi

  java_bin="$(cd "$(dirname "$java_bin")" && pwd)/$(basename "$java_bin")"
  dirname "$(dirname "$java_bin")"
}

"$PROJECT_ROOT/scripts/macos/compilacion/build-cifrador.sh"

JAVA_HOME_RESOLVED="$(resolve_java_home)"
NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
PREBUILT_NATIVE_ROOT="$PROJECT_ROOT/prebuilt/${NATIVE_CLASSIFIER}"
PREBUILT_JAVA_ROOT="$PROJECT_ROOT/prebuilt/java"
IMAGE_ROOT="$PROJECT_ROOT/dist/macos/image/${APP_NAME}"
APP_ROOT="$IMAGE_ROOT/app"
RUNTIME_ROOT="$IMAGE_ROOT/runtime"
NATIVE_ROOT="$IMAGE_ROOT/libs/${NATIVE_CLASSIFIER}"
JAR_PATH="$PREBUILT_JAVA_ROOT/ElGamalCipher-${APP_VERSION}.jar"
LAUNCHER_PATH="$IMAGE_ROOT/${APP_NAME}"

if [[ ! -f "$PREBUILT_NATIVE_ROOT/libvecj-2.2.0.dylib" || ! -f "$PREBUILT_NATIVE_ROOT/libvmgj-1.3.0.dylib" ]]; then
  echo "No se encontraron las bibliotecas nativas prebuilts en: $PREBUILT_NATIVE_ROOT" >&2
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "No se encontro el JAR canonico esperado: $JAR_PATH" >&2
  exit 1
fi

rm -rf "$IMAGE_ROOT"
mkdir -p "$APP_ROOT" "$RUNTIME_ROOT" "$NATIVE_ROOT"

cp "$JAR_PATH" "$APP_ROOT/"
# Copiar JNI libs y sus dependencias transitivas (libvec, libgmpmee, libgmp)
for dylib in "$PREBUILT_NATIVE_ROOT"/*.dylib; do
  cp -f "$dylib" "$NATIVE_ROOT/"
done
cp -a "$PROJECT_ROOT/recursos" "$IMAGE_ROOT/"
cp -a "$JAVA_HOME_RESOLVED/." "$RUNTIME_ROOT/"

cat > "$LAUNCHER_PATH" <<LAUNCHER
#!/usr/bin/env bash
set -euo pipefail
APP_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="\$APP_DIR/runtime/bin/java"
NATIVE_DIR="\$APP_DIR/libs/${NATIVE_CLASSIFIER}"
APP_JAR="\$APP_DIR/app/ElGamalCipher-${APP_VERSION}.jar"

if [[ ! -x "\$JAVA_BIN" ]]; then
  echo "No se encontro runtime embebido en: \$JAVA_BIN" >&2
  exit 1
fi

export DYLD_LIBRARY_PATH="\$NATIVE_DIR:\${DYLD_LIBRARY_PATH:-}"
exec "\$JAVA_BIN" -Djava.library.path="\$NATIVE_DIR" -jar "\$APP_JAR" "\$@"
LAUNCHER

chmod +x "$LAUNCHER_PATH"

printf '\n[macos-portable] Imagen generada en: %s\n' "$IMAGE_ROOT"
printf '[macos-portable] Launcher: %s\n' "$LAUNCHER_PATH"
printf '[macos-portable] Librerias incluidas:\n'
find "$NATIVE_ROOT" -maxdepth 1 -type f -name '*.dylib' -print | sed 's#^#  - #' | sort
