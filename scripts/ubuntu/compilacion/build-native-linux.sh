#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SOURCE_ROOT="${VERIFICATUM_SOURCE_ROOT:-$PROJECT_ROOT/native/verificatum-src}"
BUILD_ROOT="$PROJECT_ROOT/.build/native-linux"
LOG_DIR="$BUILD_ROOT/logs"

VEC_VERSION="2.5.0"
GMPMEE_VERSION="2.1.0"
VECJ_VERSION="2.2.0"
VMGJ_VERSION="1.3.0"

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

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[linux-native] ERROR: falta el comando requerido: $1" >&2
    exit 1
  fi
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi

  local javac_bin
  javac_bin="$(command -v javac || true)"
  if [[ -z "$javac_bin" ]]; then
    echo "[linux-native] ERROR: no se encontró javac ni JAVA_HOME." >&2
    exit 1
  fi

  javac_bin="$(readlink -f "$javac_bin")"
  dirname "$(dirname "$javac_bin")"
}

resolve_gmp_include_dir() {
  local multiarch
  multiarch="$(gcc -print-multiarch 2>/dev/null || true)"

  for dir in \
    "/usr/include/${multiarch}" \
    "/usr/include/x86_64-linux-gnu" \
    "/usr/include/aarch64-linux-gnu" \
    "/usr/include"; do
    if [[ -n "$dir" && -f "$dir/gmp.h" ]]; then
      echo "$dir"
      return 0
    fi
  done

  echo "[linux-native] ERROR: no se encontró gmp.h en el sistema." >&2
  exit 1
}

resolve_parallelism() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
    return 0
  fi
  getconf _NPROCESSORS_ONLN
}

copy_source_tree() {
  local src="$1"
  local dst="$2"
  rm -rf "$dst"
  mkdir -p "$dst"
  cp -a "$src/." "$dst/"
}

build_autotools_component() {
  local label="$1"
  local version="$2"
  local src="$3"
  local build_dir="$4"
  local cppflags="$5"
  local with_version_file="${6:-1}"
  local configure_log="$LOG_DIR/${label}-configure.log"
  local autoreconf_log="$LOG_DIR/${label}-autoreconf.log"
  local native_autoreconf_log="$LOG_DIR/${label}-native-autoreconf.log"
  local make_log="$LOG_DIR/${label}-make.log"
  local install_log="$LOG_DIR/${label}-install.log"

  copy_source_tree "$src" "$build_dir"
  mkdir -p "$build_dir/m4"

  (
    export JAVA_HOME="$JAVA_HOME_RESOLVED"
    export CPPFLAGS="$cppflags"
    export LIBRARY_PATH="$PREFIX_ROOT/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
    export LD_LIBRARY_PATH="$PREFIX_ROOT/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

    cd "$build_dir"
    if [[ "$with_version_file" == "1" ]]; then
      printf '%s' "$version" > .version.m4
      if [[ -d "$build_dir/native" ]]; then
        printf '%s' "$version" > "$build_dir/native/.version.m4"
      fi
    fi

    if [[ -d "$build_dir/native" ]]; then
      (
        cd "$build_dir/native"
        autoreconf -fi >"$native_autoreconf_log" 2>&1
      )
    fi

    autoreconf -fi >"$autoreconf_log" 2>&1
    ./configure --prefix="$PREFIX_ROOT" >"$configure_log" 2>&1
    make -j"$PARALLELISM" >"$make_log" 2>&1
    make install >"$install_log" 2>&1
  )
}

require_cmd autoreconf
require_cmd gcc
require_cmd javac
require_cmd make
require_cmd file

if [[ ! -d "$SOURCE_ROOT" ]]; then
  echo "[linux-native] ERROR: no se encontró el árbol de fuentes Verificatum en $SOURCE_ROOT" >&2
  exit 1
fi

VEC_SOURCE="$SOURCE_ROOT/verificatum-vec-$VEC_VERSION"
GMPMEE_SOURCE="$SOURCE_ROOT/verificatum-gmpmee-$GMPMEE_VERSION"
VECJ_SOURCE="$SOURCE_ROOT/verificatum-vecj-$VECJ_VERSION"
VMGJ_SOURCE="$SOURCE_ROOT/verificatum-vmgj-$VMGJ_VERSION"

for path in "$VEC_SOURCE" "$GMPMEE_SOURCE" "$VECJ_SOURCE" "$VMGJ_SOURCE"; do
  if [[ ! -d "$path" ]]; then
    echo "[linux-native] ERROR: falta fuente requerida: $path" >&2
    exit 1
  fi
done

JAVA_HOME_RESOLVED="$(resolve_java_home)"
GMP_INCLUDE_DIR="$(resolve_gmp_include_dir)"
PARALLELISM="$(resolve_parallelism)"
NATIVE_CLASSIFIER="$(resolve_linux_classifier)"
CLASSIFIER_BUILD_ROOT="$BUILD_ROOT/$NATIVE_CLASSIFIER"
PREFIX_ROOT="$CLASSIFIER_BUILD_ROOT/prefix"
PREBUILT_NATIVE_ROOT="$PROJECT_ROOT/prebuilt/$NATIVE_CLASSIFIER"

mkdir -p "$LOG_DIR" "$CLASSIFIER_BUILD_ROOT" "$PREBUILT_NATIVE_ROOT"
rm -rf "$PREFIX_ROOT"
mkdir -p "$PREFIX_ROOT"

echo "[linux-native] Compilando vec/gmpmee/vecj/vmgj para $NATIVE_CLASSIFIER..."

build_autotools_component \
  "vec" \
  "$VEC_VERSION" \
  "$VEC_SOURCE" \
  "$CLASSIFIER_BUILD_ROOT/verificatum-vec-$VEC_VERSION" \
  "-I$GMP_INCLUDE_DIR"

build_autotools_component \
  "gmpmee" \
  "$GMPMEE_VERSION" \
  "$GMPMEE_SOURCE" \
  "$CLASSIFIER_BUILD_ROOT/verificatum-gmpmee-$GMPMEE_VERSION" \
  "-I$PREFIX_ROOT/include -I$GMP_INCLUDE_DIR"

build_autotools_component \
  "vecj" \
  "$VECJ_VERSION" \
  "$VECJ_SOURCE" \
  "$CLASSIFIER_BUILD_ROOT/verificatum-vecj-$VECJ_VERSION" \
  "-I$PREFIX_ROOT/include -I$GMP_INCLUDE_DIR"

build_autotools_component \
  "vmgj" \
  "$VMGJ_VERSION" \
  "$VMGJ_SOURCE" \
  "$CLASSIFIER_BUILD_ROOT/verificatum-vmgj-$VMGJ_VERSION" \
  "-I$PREFIX_ROOT/include -I$GMP_INCLUDE_DIR"

cp -L "$PREFIX_ROOT/lib/libvecj-$VECJ_VERSION.so" "$PREBUILT_NATIVE_ROOT/libvecj-$VECJ_VERSION.so"
cp -L "$PREFIX_ROOT/lib/libvmgj-$VMGJ_VERSION.so" "$PREBUILT_NATIVE_ROOT/libvmgj-$VMGJ_VERSION.so"

file "$PREBUILT_NATIVE_ROOT/libvecj-$VECJ_VERSION.so" "$PREBUILT_NATIVE_ROOT/libvmgj-$VMGJ_VERSION.so"

printf '[linux-native] JNI Linux exportadas en: %s\n' "$PREBUILT_NATIVE_ROOT"
printf '[linux-native] Logs detallados en: %s\n' "$LOG_DIR"
