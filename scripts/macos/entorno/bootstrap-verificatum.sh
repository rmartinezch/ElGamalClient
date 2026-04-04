#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# bootstrap-verificatum.sh  —  macOS
#
# 1. Valida artefactos Maven locales de Verificatum en native/verificatum-jars
# 2. Instala prerrequisitos (JDK, GMP, autotools) en .tools/ local
#    si Homebrew no está disponible (solo necesario para compilación nativa).
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

cd "$PROJECT_ROOT"

echo "[macos] Repositorio: $PROJECT_ROOT"
echo "[macos] Validando artefactos Maven locales de Verificatum..."

required=(
  "native/verificatum-jars/com/verificatum/verificatum-vmgj/1.3.0/verificatum-vmgj-1.3.0.jar"
  "native/verificatum-jars/com/verificatum/verificatum-vecj/2.2.0/verificatum-vecj-2.2.0.jar"
  "native/verificatum-jars/com/verificatum/verificatum-vcr-vmgj-vecj/3.1.0/verificatum-vcr-vmgj-vecj-3.1.0.jar"
)

missing=0
for artifact in "${required[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    echo "[macos] FALTA: $artifact"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  echo "[macos] No se encontraron todos los artefactos en native/verificatum-jars."
  echo "[macos] Verifique que el directorio native/verificatum-jars contenga los JARs de Verificatum."
  exit 1
fi

echo "[macos] Artefactos locales presentes en native/verificatum-jars."

# ========================== Herramientas de compilación ====================
# Solo necesario si se va a compilar código nativo (autotools + GMP).
# Si se desea solo compilar el JAR, basta con JDK + Maven Wrapper (mvnw).

TOOLS_DIR="$PROJECT_ROOT/.tools"

# Homebrew (opcional — si está disponible, instala dependencias nativas)
if command -v brew >/dev/null 2>&1; then
  BREW_DEPS=(gmp autoconf automake libtool)
  MISSING_DEPS=()
  for dep in "${BREW_DEPS[@]}"; do
    if ! brew list --formula "$dep" >/dev/null 2>&1; then
      MISSING_DEPS+=("$dep")
    fi
  done
  if [[ ${#MISSING_DEPS[@]} -gt 0 ]]; then
    echo "[macos-bootstrap] Instalando paquetes faltantes via Homebrew: ${MISSING_DEPS[*]}"
    brew install "${MISSING_DEPS[@]}"
  fi
else
  echo "[macos-bootstrap] Homebrew no disponible. Instalando herramientas localmente en $TOOLS_DIR"
  mkdir -p "$TOOLS_DIR"

  # ----------- Autotools (m4, autoconf, automake, libtool) ------------------
  if ! command -v autoreconf >/dev/null 2>&1; then
    AUTOTOOLS_PREFIX="$TOOLS_DIR/autotools"
    if [[ ! -d "$AUTOTOOLS_PREFIX/bin" ]]; then
      echo "[macos-bootstrap] Compilando m4, autoconf, automake, libtool localmente..."
      AT_BUILD="$TOOLS_DIR/autotools-build"
      mkdir -p "$AT_BUILD" "$AUTOTOOLS_PREFIX"

      # GNU m4 (requerido >= 1.4.8 por autoconf)
      M4_VER="1.4.19"
      curl -fSL "https://ftp.gnu.org/gnu/m4/m4-${M4_VER}.tar.gz" | tar xz -C "$AT_BUILD"
      (cd "$AT_BUILD/m4-${M4_VER}" && ./configure --prefix="$AUTOTOOLS_PREFIX" && make && make install)
      export PATH="$AUTOTOOLS_PREFIX/bin:$PATH"

      # autoconf
      AUTOCONF_VER="2.72"
      curl -fSL "https://ftp.gnu.org/gnu/autoconf/autoconf-${AUTOCONF_VER}.tar.gz" | tar xz -C "$AT_BUILD"
      (cd "$AT_BUILD/autoconf-${AUTOCONF_VER}" && ./configure --prefix="$AUTOTOOLS_PREFIX" && make && make install)

      # automake
      AUTOMAKE_VER="1.17"
      curl -fSL "https://ftp.gnu.org/gnu/automake/automake-${AUTOMAKE_VER}.tar.gz" | tar xz -C "$AT_BUILD"
      (cd "$AT_BUILD/automake-${AUTOMAKE_VER}" && ./configure --prefix="$AUTOTOOLS_PREFIX" && make && make install)

      # libtool
      LIBTOOL_VER="2.5.4"
      curl -fSL "https://ftp.gnu.org/gnu/libtool/libtool-${LIBTOOL_VER}.tar.gz" | tar xz -C "$AT_BUILD"
      (cd "$AT_BUILD/libtool-${LIBTOOL_VER}" && ./configure --prefix="$AUTOTOOLS_PREFIX" && make && make install)

      rm -rf "$AT_BUILD"
    fi
    export PATH="$AUTOTOOLS_PREFIX/bin:$PATH"
  fi

  # ----------- GMP (no requiere sudo) --------------------------------------
  GMP_PREFIX="$TOOLS_DIR/gmp"
  if [[ ! -f "$GMP_PREFIX/include/gmp.h" ]]; then
    echo "[macos-bootstrap] Compilando GMP localmente..."
    GMP_BUILD="$TOOLS_DIR/gmp-build"
    GMP_VER="6.3.0"
    mkdir -p "$GMP_BUILD"
    curl -fSL "https://ftp.gnu.org/gnu/gmp/gmp-${GMP_VER}.tar.xz" | tar xJ -C "$GMP_BUILD"
    (cd "$GMP_BUILD/gmp-${GMP_VER}" && ./configure --prefix="$GMP_PREFIX" --enable-shared --with-pic && make -j"$(sysctl -n hw.logicalcpu)" && make install)
    rm -rf "$GMP_BUILD"
  fi
  export CPPFLAGS="-I$GMP_PREFIX/include ${CPPFLAGS:-}"
  export LDFLAGS="-L$GMP_PREFIX/lib ${LDFLAGS:-}"
  export LIBRARY_PATH="$GMP_PREFIX/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
  export DYLD_LIBRARY_PATH="$GMP_PREFIX/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
fi

# ========================== JDK (independiente de Homebrew) =================
# Usa .tools/jdk-21 local, Homebrew openjdk, o JAVA_HOME del entorno.
if [[ -z "${JAVA_HOME:-}" ]] || ! command -v javac >/dev/null 2>&1; then
  # Intentar .tools/jdk-21 existente
  if [[ -d "$TOOLS_DIR/jdk-21/bin" ]]; then
    export JAVA_HOME="$TOOLS_DIR/jdk-21"
    export PATH="$JAVA_HOME/bin:$PATH"
  # Intentar Homebrew openjdk
  elif command -v brew >/dev/null 2>&1; then
    OPENJDK_HOME="$(brew --prefix openjdk 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [[ -d "$OPENJDK_HOME" ]]; then
      export JAVA_HOME="$OPENJDK_HOME"
      export PATH="$JAVA_HOME/bin:$PATH"
    fi
  fi
fi

# Si aún no hay JDK, descargar Temurin localmente
if ! command -v javac >/dev/null 2>&1; then
  mkdir -p "$TOOLS_DIR"
  JDK_DIR="$TOOLS_DIR/jdk-21"
  if [[ ! -d "$JDK_DIR" ]]; then
    echo "[macos-bootstrap] Descargando JDK 21 (Eclipse Temurin)..."
    JDK_TAR="$TOOLS_DIR/jdk21.tar.gz"
    ARCH="$(uname -m)"
    if [[ "$ARCH" == "arm64" ]]; then
      JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
    else
      JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
    fi
    curl -fSL -o "$JDK_TAR" "$JDK_URL"
    mkdir -p "$JDK_DIR"
    tar xzf "$JDK_TAR" -C "$JDK_DIR" --strip-components=1
    rm -f "$JDK_TAR"
    if [[ -d "$JDK_DIR/Contents/Home" ]]; then
      mv "$JDK_DIR" "$JDK_DIR.tmp"
      mv "$JDK_DIR.tmp/Contents/Home" "$JDK_DIR"
      rm -rf "$JDK_DIR.tmp"
    fi
  fi
  export JAVA_HOME="$JDK_DIR"
  export PATH="$JDK_DIR/bin:$PATH"
fi

echo "[macos-bootstrap] JAVA_HOME=$JAVA_HOME"
javac -version
