#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SOURCE_ROOT="${VERIFICATUM_SOURCE_ROOT:-$PROJECT_ROOT/native/verificatum-src}"
BUILD_ROOT="$PROJECT_ROOT/.build/native-macos"
LOG_DIR="$BUILD_ROOT/logs"

VEC_VERSION="2.5.0"
GMPMEE_VERSION="2.1.0"
VECJ_VERSION="2.2.0"
VMGJ_VERSION="1.3.0"

resolve_macos_classifier() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "macos-x64" ;;
    arm64|aarch64) echo "macos-arm64" ;;
    *) echo "macos-${arch}" ;;
  esac
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[macos-native] ERROR: falta el comando requerido: $1" >&2
    exit 1
  fi
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi

  # Herramientas locales del proyecto
  if [[ -x "$PROJECT_ROOT/.tools/jdk-21/bin/javac" ]]; then
    echo "$PROJECT_ROOT/.tools/jdk-21"
    return 0
  fi

  local javac_bin
  javac_bin="$(command -v javac || true)"
  if [[ -z "$javac_bin" ]]; then
    echo "[macos-native] ERROR: no se encontro javac ni JAVA_HOME." >&2
    exit 1
  fi

  javac_bin="$(cd "$(dirname "$javac_bin")" && pwd)/$(basename "$javac_bin")"
  dirname "$(dirname "$javac_bin")"
}

resolve_gmp_include_dir() {
  local candidates=(
    "$PROJECT_ROOT/.tools/gmp/include"
    "${HOMEBREW_PREFIX:-}/include"
    "/opt/homebrew/include"
    "/usr/local/include"
    "/usr/include"
  )

  local dir
  for dir in "${candidates[@]}"; do
    if [[ -n "$dir" && -f "$dir/gmp.h" ]]; then
      echo "$dir"
      return 0
    fi
  done

  echo "[macos-native] ERROR: no se encontro gmp.h. Instale gmp (brew install gmp)." >&2
  exit 1
}

resolve_gmp_lib_dir() {
  local candidates=(
    "$PROJECT_ROOT/.tools/gmp/lib"
    "${HOMEBREW_PREFIX:-}/lib"
    "/opt/homebrew/lib"
    "/usr/local/lib"
    "/usr/lib"
  )

  local dir
  for dir in "${candidates[@]}"; do
    if [[ -n "$dir" && -f "$dir/libgmp.dylib" ]]; then
      echo "$dir"
      return 0
    fi
  done

  echo "[macos-native] ERROR: no se encontro libgmp.dylib. Instale gmp (brew install gmp)." >&2
  exit 1
}

resolve_parallelism() {
  sysctl -n hw.logicalcpu
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

  # Parchear configure.ac para macOS:
  # 1) extract_GMP_CFLAGS.c necesita CPPFLAGS para encontrar gmp.h
  # 2) AX_JNI_INCLUDE_DIR no detecta Temurin en Darwin — usamos C_INCLUDE_PATH
  for _confac in "$build_dir/configure.ac" "$build_dir/native/configure.ac"; do
    if [[ -f "$_confac" ]]; then
      sed -i '' \
        's|\${CC} extract_GMP_CFLAGS.c|\${CC} \${CPPFLAGS} extract_GMP_CFLAGS.c|g' \
        "$_confac"
    fi
  done

  # Parchear ax_jni_include_dir.m4 para detectar JDK Temurin/Adoptium en Darwin.
  # El macro original busca $JTOPDIR/Headers (layout Apple JDK); Temurin usa
  # $JTOPDIR/include como en Linux. También añade "darwin" como subdirectorio
  # para jni_md.h.
  local _jni_m4="$build_dir/native/m4/ax_jni_include_dir.m4"
  if [[ -f "$_jni_m4" ]]; then
    # 1) Fallback de Headers → include en caso Darwin
    sed -i '' \
      '/darwin\*).*_JTOPDIR=/{
        N
        s|_JINC="\$_JTOPDIR/Headers"|_JINC="$_JTOPDIR/Headers"\
                        if test ! -f "$_JINC/jni.h"; then _JINC="$_JTOPDIR/include"; fi|
      }' \
      "$_jni_m4"
    # 2) Añadir darwin como subdirectorio de JNI includes
    sed -i '' \
      's|^bsdi\*).*_JNI_INC_SUBDIRS="bsdos";;|darwin*)         _JNI_INC_SUBDIRS="darwin";;\
bsdi*)          _JNI_INC_SUBDIRS="bsdos";;|' \
      "$_jni_m4"
  fi

  (
    export JAVA_HOME="$JAVA_HOME_RESOLVED"
    # Incluir JNI headers explícitamente (el macro AX_JNI_INCLUDE_DIR no
    # detecta correctamente Temurin/Adoptium en Darwin moderno)
    local jni_include="-I$JAVA_HOME_RESOLVED/include"
    local c_include_extra="$JAVA_HOME_RESOLVED/include"
    if [[ -d "$JAVA_HOME_RESOLVED/include/darwin" ]]; then
      jni_include="$jni_include -I$JAVA_HOME_RESOLVED/include/darwin"
      c_include_extra="$c_include_extra:$JAVA_HOME_RESOLVED/include/darwin"
    fi
    export CPPFLAGS="$cppflags $jni_include"
    # clang trata -Wstrict-prototypes como error con -Werror -pedantic;
    # el código Verificatum usa prototipos vacíos () en lugar de (void).
    export CFLAGS="-Wno-strict-prototypes ${CFLAGS:-}"
    # C_INCLUDE_PATH es usado por el compilador incluso sin -I (necesario
    # para extract_GMP_CFLAGS.c y la detección de jni.h en configure)
    export C_INCLUDE_PATH="$GMP_INCLUDE_DIR:${c_include_extra}${C_INCLUDE_PATH:+:$C_INCLUDE_PATH}"
    export LDFLAGS="-L$PREFIX_ROOT/lib -L$GMP_LIB_DIR ${LDFLAGS:-}"
    export LIBRARY_PATH="$PREFIX_ROOT/lib:$GMP_LIB_DIR${LIBRARY_PATH:+:$LIBRARY_PATH}"

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
    # -j1 requerido: los Makefile de Verificatum tienen dependencias de
    # recetas incompatibles con make paralelo (jar→cp race condition).
    make -j1 >"$make_log" 2>&1
    make install >"$install_log" 2>&1
  )
}

# Asegurar herramientas locales en PATH
TOOLS_DIR="$PROJECT_ROOT/.tools"
if [[ -d "$TOOLS_DIR/jdk-21/bin" ]]; then
  export JAVA_HOME="${JAVA_HOME:-$TOOLS_DIR/jdk-21}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -d "$TOOLS_DIR/autotools/bin" ]]; then
  export PATH="$TOOLS_DIR/autotools/bin:$PATH"
fi
if [[ -d "$TOOLS_DIR/gmp" ]]; then
  export CPPFLAGS="-I$TOOLS_DIR/gmp/include ${CPPFLAGS:-}"
  export LDFLAGS="-L$TOOLS_DIR/gmp/lib ${LDFLAGS:-}"
  export LIBRARY_PATH="$TOOLS_DIR/gmp/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
  export DYLD_LIBRARY_PATH="$TOOLS_DIR/gmp/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
fi

require_cmd autoreconf
require_cmd clang
require_cmd javac
require_cmd make
require_cmd file

if [[ ! -d "$SOURCE_ROOT" ]]; then
  echo "[macos-native] ERROR: no se encontro el arbol de fuentes Verificatum en $SOURCE_ROOT" >&2
  exit 1
fi

VEC_SOURCE="$SOURCE_ROOT/verificatum-vec-$VEC_VERSION"
GMPMEE_SOURCE="$SOURCE_ROOT/verificatum-gmpmee-$GMPMEE_VERSION"
VECJ_SOURCE="$SOURCE_ROOT/verificatum-vecj-$VECJ_VERSION"
VMGJ_SOURCE="$SOURCE_ROOT/verificatum-vmgj-$VMGJ_VERSION"

for path in "$VEC_SOURCE" "$GMPMEE_SOURCE" "$VECJ_SOURCE" "$VMGJ_SOURCE"; do
  if [[ ! -d "$path" ]]; then
    echo "[macos-native] ERROR: falta fuente requerida: $path" >&2
    exit 1
  fi
done

JAVA_HOME_RESOLVED="$(resolve_java_home)"
GMP_INCLUDE_DIR="$(resolve_gmp_include_dir)"
GMP_LIB_DIR="$(resolve_gmp_lib_dir)"
PARALLELISM="$(resolve_parallelism)"
NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
CLASSIFIER_BUILD_ROOT="$BUILD_ROOT/$NATIVE_CLASSIFIER"
PREFIX_ROOT="$CLASSIFIER_BUILD_ROOT/prefix"
PREBUILT_NATIVE_ROOT="$PROJECT_ROOT/prebuilt/$NATIVE_CLASSIFIER"

mkdir -p "$LOG_DIR" "$CLASSIFIER_BUILD_ROOT" "$PREBUILT_NATIVE_ROOT"
rm -rf "$PREFIX_ROOT"
mkdir -p "$PREFIX_ROOT"

echo "[macos-native] Compilando vec/gmpmee/vecj/vmgj para $NATIVE_CLASSIFIER..."

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

cp -f "$PREFIX_ROOT/lib/libvecj-$VECJ_VERSION.dylib" "$PREBUILT_NATIVE_ROOT/libvecj-$VECJ_VERSION.dylib"
cp -f "$PREFIX_ROOT/lib/libvmgj-$VMGJ_VERSION.dylib" "$PREBUILT_NATIVE_ROOT/libvmgj-$VMGJ_VERSION.dylib"
# Dependencias transitivas que el JVM debe encontrar junto a las JNI libs
cp -f "$PREFIX_ROOT/lib/libvec.0.dylib"    "$PREBUILT_NATIVE_ROOT/libvec.0.dylib"
cp -f "$PREFIX_ROOT/lib/libgmpmee.0.dylib" "$PREBUILT_NATIVE_ROOT/libgmpmee.0.dylib"
cp -f "$GMP_LIB_DIR/libgmp.10.dylib"      "$PREBUILT_NATIVE_ROOT/libgmp.10.dylib"

# Reescribir install_names para que usen @loader_path/ (portabilidad)
fix_dylib_ids_and_deps() {
  local lib="$1"
  # Cambiar el ID de la propia librería
  install_name_tool -id "@loader_path/$(basename "$lib")" "$lib"
  # Reescribir dependencias a @loader_path/
  local dep
  for dep in $(otool -L "$lib" | awk 'NR>1{print $1}' | grep -v /usr/lib); do
    local base
    base="$(basename "$dep")"
    if [[ "$dep" != "@loader_path/$base" ]]; then
      install_name_tool -change "$dep" "@loader_path/$base" "$lib"
    fi
  done
}

for dylib in "$PREBUILT_NATIVE_ROOT"/*.dylib; do
  fix_dylib_ids_and_deps "$dylib"
done

file "$PREBUILT_NATIVE_ROOT"/*.dylib

printf '[macos-native] JNI macOS exportadas en: %s\n' "$PREBUILT_NATIVE_ROOT"
printf '[macos-native] Logs detallados en: %s\n' "$LOG_DIR"
