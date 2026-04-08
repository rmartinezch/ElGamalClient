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

"$PROJECT_ROOT/scripts/ubuntu/entorno/bootstrap-verificatum.sh"

MVN="${MVN:-}"
if [[ -z "$MVN" ]]; then
  if [[ -x "$PROJECT_ROOT/mvnw" ]]; then
    MVN="$PROJECT_ROOT/mvnw"
  elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
  else
    echo "[ubuntu-build] ERROR: no se encontró Maven ni Maven Wrapper (mvnw)." >&2
    echo "  El proyecto incluye mvnw en la raíz. Verifique permisos: chmod +x mvnw" >&2
    exit 1
  fi
fi

if [[ "$SKIP_TESTS" == "1" ]]; then
  "$MVN" clean package -DskipTests
else
  "$MVN" clean package
fi

if [[ ! -f "$TARGET_JAR" ]]; then
  echo "[ubuntu-build] ERROR: no se encontró el JAR esperado tras Maven: $TARGET_JAR" >&2
  exit 1
fi

mkdir -p "$PREBUILT_JAVA_DIR"
cp "$TARGET_JAR" "$CANONICAL_JAR"

"$SCRIPT_DIR/build-native-linux.sh"

echo "[ubuntu-build] Cifrador Ubuntu compilado:"
echo "  - $CANONICAL_JAR"
