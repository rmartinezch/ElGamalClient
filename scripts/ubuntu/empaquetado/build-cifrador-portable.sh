#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"

cd "$PROJECT_ROOT"

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    echo "$JAVA_HOME"
    return
  fi

  local java_bin
  java_bin="$(command -v java || true)"
  if [[ -z "$java_bin" ]]; then
    echo "No se encontró java en PATH ni JAVA_HOME." >&2
    exit 1
  fi

  java_bin="$(readlink -f "$java_bin")"
  dirname "$(dirname "$java_bin")"
}

copy_with_soname() {
    local source_path="$1"
    local destination_dir="$2"
    cp -L "$source_path" "$destination_dir/$(basename "$source_path")"
}

resolve_linux_classifier() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "linux-x64" ;;
    aarch64|arm64) echo "linux-arm64" ;;
    i386|i486|i586|i686|x86) echo "linux-x86" ;;
    *) echo "linux-${arch}" ;;
  esac
}

SKIP_TESTS="${SKIP_TESTS:-1}" "$PROJECT_ROOT/scripts/ubuntu/compilacion/build-cifrador.sh"

JAVA_HOME_RESOLVED="$(resolve_java_home)"
NATIVE_CLASSIFIER="$(resolve_linux_classifier)"
PREBUILT_NATIVE_ROOT="$PROJECT_ROOT/prebuilt/${NATIVE_CLASSIFIER}"
PREBUILT_JAVA_ROOT="$PROJECT_ROOT/prebuilt/java"
IMAGE_ROOT="$PROJECT_ROOT/dist/linux/image/${APP_NAME}"
APP_ROOT="$IMAGE_ROOT/app"
RUNTIME_ROOT="$IMAGE_ROOT/runtime"
NATIVE_ROOT="$IMAGE_ROOT/libs/${NATIVE_CLASSIFIER}"
JAR_PATH="$PREBUILT_JAVA_ROOT/ElGamalCipher-${APP_VERSION}.jar"
LAUNCHER_PATH="$IMAGE_ROOT/${APP_NAME}"

mkdir -p "$PREBUILT_JAVA_ROOT"

if [[ ! -f "$PREBUILT_NATIVE_ROOT/libvecj-2.2.0.so" || ! -f "$PREBUILT_NATIVE_ROOT/libvmgj-1.3.0.so" ]]; then
  echo "No se encontraron las bibliotecas nativas prebuilts en: $PREBUILT_NATIVE_ROOT" >&2
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "No se encontró el JAR canónico esperado: $JAR_PATH" >&2
  exit 1
fi

rm -rf "$IMAGE_ROOT"
mkdir -p "$APP_ROOT" "$RUNTIME_ROOT" "$NATIVE_ROOT"

cp "$JAR_PATH" "$APP_ROOT/"
cp -a "$PROJECT_ROOT/recursos" "$IMAGE_ROOT/"
cp -L "$PREBUILT_NATIVE_ROOT/libvecj-2.2.0.so" "$NATIVE_ROOT/"
cp -L "$PREBUILT_NATIVE_ROOT/libvmgj-1.3.0.so" "$NATIVE_ROOT/"

mapfile -t transitive_deps < <(
  {
    ldd "$PREBUILT_NATIVE_ROOT/libvecj-2.2.0.so" || true
    ldd "$PREBUILT_NATIVE_ROOT/libvmgj-1.3.0.so" || true
  } | awk '/=> \/usr\/local\/lib\// { print $3 }' | sort -u
)

for dep in "${transitive_deps[@]}"; do
  if [[ -f "$dep" ]]; then
    copy_with_soname "$dep" "$NATIVE_ROOT"
  fi
done

cp -a "$JAVA_HOME_RESOLVED/." "$RUNTIME_ROOT/"

cat > "$LAUNCHER_PATH" <<LAUNCHER
#!/usr/bin/env bash
set -euo pipefail
APP_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="\$APP_DIR/runtime/bin/java"
NATIVE_DIR="\$APP_DIR/libs/${NATIVE_CLASSIFIER}"
APP_JAR="\$APP_DIR/app/ElGamalCipher-${APP_VERSION}.jar"

if [[ ! -x "\$JAVA_BIN" ]]; then
  echo "No se encontró runtime embebido en: \$JAVA_BIN" >&2
  exit 1
fi

export LD_LIBRARY_PATH="\$NATIVE_DIR:\${LD_LIBRARY_PATH:-}"
exec "\$JAVA_BIN" -Djava.library.path="\$NATIVE_DIR" -jar "\$APP_JAR" "\$@"
LAUNCHER

chmod +x "$LAUNCHER_PATH"

printf '\n[ubuntu-portable] Imagen generada en: %s\n' "$IMAGE_ROOT"
printf '[ubuntu-portable] Launcher: %s\n' "$LAUNCHER_PATH"
printf '[ubuntu-portable] Librerías incluidas:\n'
find "$NATIVE_ROOT" -maxdepth 1 -type f -name '*.so*' -printf '  - %f\n' | sort
