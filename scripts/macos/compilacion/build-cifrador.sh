#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APP_VERSION="${APP_VERSION:-1.1.0}"
SKIP_TESTS="${SKIP_TESTS:-1}"
PREBUILT_JAVA_DIR="$PROJECT_ROOT/prebuilt/java"
TARGET_JAR="$PROJECT_ROOT/target/ElGamalCipher-${APP_VERSION}.jar"
CANONICAL_JAR="$PREBUILT_JAVA_DIR/ElGamalCipher-${APP_VERSION}.jar"

cd "$PROJECT_ROOT"

# Asegurar herramientas locales (.tools/) en el PATH
TOOLS_DIR="$PROJECT_ROOT/.tools"
if [[ -d "$TOOLS_DIR/jdk-21/bin" ]]; then
  export JAVA_HOME="$TOOLS_DIR/jdk-21"
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

"$PROJECT_ROOT/scripts/macos/entorno/bootstrap-verificatum.sh"

if [[ "$SKIP_TESTS" == "1" ]]; then
	./mvnw clean package -DskipTests
else
	./mvnw clean package
fi

if [[ ! -f "$TARGET_JAR" ]]; then
	echo "[macos-build] ERROR: no se encontro el JAR esperado tras Maven: $TARGET_JAR" >&2
	exit 1
fi

mkdir -p "$PREBUILT_JAVA_DIR"
cp "$TARGET_JAR" "$CANONICAL_JAR"

"$SCRIPT_DIR/build-native-macos.sh"

echo "[macos-build] Cifrador macOS compilado:"
echo "  - $CANONICAL_JAR"
