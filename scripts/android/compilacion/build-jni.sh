#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"
ANDROID_DIR="$(resolve_android_project_dir "$PROJECT_ROOT")"
JNILIBS_ROOT="$PROJECT_ROOT/prebuilt/android/jniLibs"
BUILD_ROOT="$PROJECT_ROOT/.build/android"
LOG_DIR="$BUILD_ROOT/logs"
DOWNLOADS_DIR="$BUILD_ROOT/downloads"
DEFAULT_SDK_ROOT="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$DEFAULT_SDK_ROOT}"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
GMP_VERSION="6.3.0"
GMP_ARCHIVE="gmp-$GMP_VERSION.tar.xz"
GMP_URL="https://ftp.gnu.org/gnu/gmp/$GMP_ARCHIVE"
SOURCE_ROOT_CANDIDATES=(
  "$PROJECT_ROOT/native/verificatum-src"
  "${VERIFICATUM_SOURCE_ROOT:-}"
  "$HOME/mixnet"
  "$(cd "$PROJECT_ROOT/.." && pwd)/mixnet"
)

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[build-jni] ERROR: falta el comando requerido: $1" >&2
    exit 1
  fi
}

resolve_sdk_root() {
  if [[ -f "$ANDROID_DIR/local.properties" ]]; then
    local sdk_dir
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$ANDROID_DIR/local.properties" | tail -n 1)"
    if [[ -n "$sdk_dir" ]]; then
      echo "$sdk_dir"
      return 0
    fi
  fi
  echo "$ANDROID_SDK_ROOT"
}

resolve_source_root() {
  local candidate
  for candidate in "${SOURCE_ROOT_CANDIDATES[@]}"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  echo "[build-jni] ERROR: no se encontró el árbol de fuentes Verificatum." >&2
  echo "[build-jni] Defina VERIFICATUM_SOURCE_ROOT o use native/verificatum-src." >&2
  exit 1
}

download_gmp() {
  mkdir -p "$DOWNLOADS_DIR" "$LOG_DIR"
  if [[ ! -f "$DOWNLOADS_DIR/$GMP_ARCHIVE" ]]; then
    echo "[build-jni] Descargando GMP $GMP_VERSION..."
    curl -L "$GMP_URL" -o "$DOWNLOADS_DIR/$GMP_ARCHIVE"
  fi
}

build_abi() {
  local abi="$1"
  local target="$2"
  local work_root="$BUILD_ROOT/proto-$abi"
  local src_root="$work_root/src"
  local build_root="$work_root/build"
  local prefix_root="$work_root/prefix"
  local out_root="$work_root/out"
  local cc="$TOOLCHAIN/bin/${target}${API_LEVEL}-clang"
  local cxx="$TOOLCHAIN/bin/${target}${API_LEVEL}-clang++"
  local ar="$TOOLCHAIN/bin/llvm-ar"
  local ranlib="$TOOLCHAIN/bin/llvm-ranlib"
  local strip="$TOOLCHAIN/bin/llvm-strip"
  local sysroot="$TOOLCHAIN/sysroot"
  local abi_log_prefix="$LOG_DIR/${abi//[^A-Za-z0-9]/_}"
  local vec_build="$build_root/vec"
  local gmpmee_build="$build_root/gmpmee"
  local patched_vmgj="$work_root/com_verificatum_vmgj_VMG.android.c"

  echo "[build-jni] Compilando ABI $abi..."

  rm -rf "$build_root" "$prefix_root" "$out_root" "$src_root/gmp-$GMP_VERSION"
  mkdir -p "$src_root" "$build_root" "$prefix_root" "$out_root" "$JNILIBS_ROOT/$abi"

  tar -xf "$DOWNLOADS_DIR/$GMP_ARCHIVE" -C "$src_root"
  pushd "$src_root/gmp-$GMP_VERSION" >/dev/null
  ./configure \
    --host="$target" \
    --prefix="$prefix_root" \
    --disable-shared \
    --enable-static \
    --disable-assembly \
    CC="$cc" \
    CXX="$cxx" \
    AR="$ar" \
    RANLIB="$ranlib" \
    STRIP="$strip" \
    CFLAGS="-fPIC" \
    >"$abi_log_prefix-gmp-configure.log" 2>&1
  make -j"$(nproc)" >"$abi_log_prefix-gmp-make.log" 2>&1
  make install >"$abi_log_prefix-gmp-install.log" 2>&1
  popd >/dev/null

  cp -a "$VEC_SOURCE" "$vec_build"
  pushd "$vec_build" >/dev/null
  mkdir -p m4
  printf '2.5.0' > .version.m4
  autoreconf -fi >"$abi_log_prefix-vec-autoreconf.log" 2>&1
  ./configure \
    --host="$target" \
    --prefix="$prefix_root" \
    --disable-shared \
    --enable-static \
    CC="$cc" \
    CXX="$cxx" \
    AR="$ar" \
    RANLIB="$ranlib" \
    STRIP="$strip" \
    CPPFLAGS="-I$prefix_root/include" \
    LDFLAGS="-L$prefix_root/lib" \
    CFLAGS="-fPIC" \
    >"$abi_log_prefix-vec-configure.log" 2>&1
  make -j"$(nproc)" >"$abi_log_prefix-vec-make.log" 2>&1
  make install >"$abi_log_prefix-vec-install.log" 2>&1
  popd >/dev/null

  cp -a "$GMPMEE_SOURCE" "$gmpmee_build"
  pushd "$gmpmee_build" >/dev/null
  autoreconf -fi >"$abi_log_prefix-gmpmee-autoreconf.log" 2>&1
  ./configure \
    --host="$target" \
    --prefix="$prefix_root" \
    --disable-shared \
    --enable-static \
    CC="$cc" \
    CXX="$cxx" \
    AR="$ar" \
    RANLIB="$ranlib" \
    STRIP="$strip" \
    CPPFLAGS="-I$prefix_root/include" \
    LDFLAGS="-L$prefix_root/lib" \
    CFLAGS="-fPIC" \
    >"$abi_log_prefix-gmpmee-configure.log" 2>&1
  make -j"$(nproc)" libgmpmee.la >"$abi_log_prefix-gmpmee-make.log" 2>&1
  cp .libs/libgmpmee.a "$prefix_root/lib/libgmpmee.a"
  cp gmpmee.h "$prefix_root/include/gmpmee.h"
  popd >/dev/null

  "$cc" -shared -fPIC -O3 -Wall -W -Werror -Wno-unused-parameter \
    -I"$sysroot/usr/include" \
    -I"$prefix_root/include" \
    -o "$out_root/libvecj-2.2.0.so" \
    "$VECJ_SOURCE/native/com_verificatum_vecj_VEC.c" \
    "$VECJ_SOURCE/native/convert.c" \
    "$prefix_root/lib/libvec.a" \
    "$prefix_root/lib/libgmp.a" \
    -lm \
    >"$abi_log_prefix-vecj-build.log" 2>&1

  cp "$VMGJ_SOURCE/native/com_verificatum_vmgj_VMG.c" "$patched_vmgj"
  perl -0pi -e 's/#include <stdlib.h>/#include <stdlib.h>\n#include <stdint.h>/ unless /stdint.h/; s/\(long\)/(intptr_t)/g' "$patched_vmgj"

  "$cc" -shared -fPIC -O3 -Wall -W -Werror -Wno-unused-parameter \
    -I"$sysroot/usr/include" \
    -I"$prefix_root/include" \
    -I"$VMGJ_SOURCE/native" \
    -o "$out_root/libvmgj-1.3.0.so" \
    "$patched_vmgj" \
    "$VMGJ_SOURCE/native/convert.c" \
    "$prefix_root/lib/libgmpmee.a" \
    "$prefix_root/lib/libgmp.a" \
    -lm \
    >"$abi_log_prefix-vmgj-build.log" 2>&1

  "$strip" --strip-unneeded "$out_root/libvecj-2.2.0.so"
  "$strip" --strip-unneeded "$out_root/libvmgj-1.3.0.so"

  cp "$out_root/libvecj-2.2.0.so" "$JNILIBS_ROOT/$abi/"
  cp "$out_root/libvmgj-1.3.0.so" "$JNILIBS_ROOT/$abi/"

  file "$JNILIBS_ROOT/$abi/libvecj-2.2.0.so" "$JNILIBS_ROOT/$abi/libvmgj-1.3.0.so"
}

require_cmd curl
require_cmd tar
require_cmd autoreconf
require_cmd make
require_cmd perl
require_cmd file

ANDROID_SDK_ROOT="$(resolve_sdk_root)"
if [[ ! -d "$ANDROID_SDK_ROOT" ]]; then
  echo "[build-jni] ERROR: no se encontró ANDROID_SDK_ROOT en $ANDROID_SDK_ROOT" >&2
  exit 1
fi

NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "[build-jni] ERROR: no se encontró el NDK esperado: $NDK_ROOT" >&2
  exit 1
fi

SOURCE_ROOT="$(resolve_source_root)"
VEC_SOURCE="$SOURCE_ROOT/verificatum-vec-2.5.0"
GMPMEE_SOURCE="$SOURCE_ROOT/verificatum-gmpmee-2.1.0"
VECJ_SOURCE="$SOURCE_ROOT/verificatum-vecj-2.2.0"
VMGJ_SOURCE="$SOURCE_ROOT/verificatum-vmgj-1.3.0"

for path in "$VEC_SOURCE" "$GMPMEE_SOURCE" "$VECJ_SOURCE" "$VMGJ_SOURCE"; do
  if [[ ! -d "$path" ]]; then
    echo "[build-jni] ERROR: falta fuente requerida: $path" >&2
    exit 1
  fi
done

download_gmp
build_abi "x86_64" "x86_64-linux-android"
build_abi "arm64-v8a" "aarch64-linux-android"

echo "[build-jni] OK: bibliotecas JNI Android generadas en $JNILIBS_ROOT"
echo "[build-jni] Logs detallados en $LOG_DIR"
