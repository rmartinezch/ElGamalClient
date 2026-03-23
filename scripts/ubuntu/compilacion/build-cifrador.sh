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

if [[ "$SKIP_TESTS" == "1" ]]; then
  mvn clean package -DskipTests
else
  mvn clean package
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
