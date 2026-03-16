#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"
SKIP_ANDROID_JNI="${SKIP_ANDROID_JNI:-1}"
INCLUDE_TEST_APK="${INCLUDE_TEST_APK:-1}"
ANDROID_DIR="$PROJECT_ROOT/android"
IMAGE_ROOT="$PROJECT_ROOT/dist/android/image/$APP_NAME"
APK_DIR="$IMAGE_ROOT/apk"
SCRIPT_DIR="$IMAGE_ROOT/scripts"
METADATA_DIR="$IMAGE_ROOT/metadata"
RESOURCE_DIR="$IMAGE_ROOT/recursos"
MAIN_APK_SOURCE="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK_SOURCE="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MAIN_APK_NAME="$APP_NAME.apk"
TEST_APK_NAME="pruebas_auto.apk"
PUBLIC_KEY_SOURCE="$PROJECT_ROOT/recursos/publicKey"
VOTES_SOURCE="$PROJECT_ROOT/recursos/shuffled_votes.txt"

cd "$PROJECT_ROOT"

SKIP_ANDROID_JNI="$SKIP_ANDROID_JNI" "$PROJECT_ROOT/scripts/android/build-debug.sh"

if [[ "$INCLUDE_TEST_APK" == "1" ]]; then
  (
    cd "$ANDROID_DIR"
    ./gradlew :app:assembleAndroidTest
  )
fi

if [[ ! -f "$MAIN_APK_SOURCE" ]]; then
  echo "[android-portable] ERROR: falta APK principal: $MAIN_APK_SOURCE" >&2
  exit 1
fi

if [[ "$INCLUDE_TEST_APK" == "1" && ! -f "$TEST_APK_SOURCE" ]]; then
  echo "[android-portable] ERROR: falta APK de pruebas: $TEST_APK_SOURCE" >&2
  exit 1
fi

rm -rf "$IMAGE_ROOT"
mkdir -p "$APK_DIR" "$SCRIPT_DIR" "$METADATA_DIR" "$RESOURCE_DIR"

cp "$MAIN_APK_SOURCE" "$APK_DIR/$MAIN_APK_NAME"
if [[ "$INCLUDE_TEST_APK" == "1" ]]; then
  cp "$TEST_APK_SOURCE" "$APK_DIR/$TEST_APK_NAME"
fi
cp "$PUBLIC_KEY_SOURCE" "$RESOURCE_DIR/publicKey"
cp "$VOTES_SOURCE" "$RESOURCE_DIR/shuffled_votes.txt"

cat > "$SCRIPT_DIR/install.sh" <<INSTALL
#!/usr/bin/env bash
set -euo pipefail
APP_DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
ADB_BIN="\${ADB:-adb}"
MAIN_APK="\$APP_DIR/apk/$MAIN_APK_NAME"
TEST_APK="\$APP_DIR/apk/$TEST_APK_NAME"

"\$ADB_BIN" install -r "\$MAIN_APK"
if [[ -f "\$TEST_APK" && "\${INSTALL_TEST_APK:-1}" == "1" ]]; then
  "\$ADB_BIN" install -r "\$TEST_APK"
fi

echo "[android-portable] Instalacion completada."
INSTALL

cat > "$SCRIPT_DIR/run-smoke-test.sh" <<SMOKE
#!/usr/bin/env bash
set -euo pipefail
ADB_BIN="\${ADB:-adb}"
TEST_RUNNER="pe.gob.onpe.votodigital.cifrador.android.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="pe.gob.onpe.votodigital.cifrador.android.AndroidPhase2InstrumentationTest#smokeTestCargaJniYEjecutaOperacionesBasicas"

"\$ADB_BIN" shell am instrument -w -r \
  -e class "\$TEST_CLASS" \
  "\$TEST_RUNNER"
SMOKE

cat > "$IMAGE_ROOT/README.txt" <<README
$APP_NAME Android portable

Contenido:
- apk/$MAIN_APK_NAME
$(if [[ "$INCLUDE_TEST_APK" == "1" ]]; then echo "- apk/$TEST_APK_NAME"; fi)
- recursos/publicKey
- recursos/shuffled_votes.txt
- scripts/install.sh
- scripts/run-smoke-test.sh
- metadata/checksums.sha256

Uso rapido:
1. adb devices
2. ./scripts/install.sh
3. ./scripts/run-smoke-test.sh

Requisitos:
- adb en PATH
- emulador o dispositivo Android disponible
README

(
  cd "$IMAGE_ROOT"
  sha256sum "apk/$MAIN_APK_NAME" > "$METADATA_DIR/checksums.sha256"
  if [[ -f "apk/$TEST_APK_NAME" ]]; then
    sha256sum "apk/$TEST_APK_NAME" >> "$METADATA_DIR/checksums.sha256"
  fi
)

chmod +x "$SCRIPT_DIR/install.sh" "$SCRIPT_DIR/run-smoke-test.sh"

printf '\n[android-portable] Imagen generada en: %s\n' "$IMAGE_ROOT"
printf '[android-portable] APK principal: %s\n' "$APK_DIR/$MAIN_APK_NAME"
if [[ "$INCLUDE_TEST_APK" == "1" ]]; then
  printf '[android-portable] APK pruebas: %s\n' "$APK_DIR/$TEST_APK_NAME"
fi
printf '[android-portable] Scripts incluidos:\n'
find "$SCRIPT_DIR" -maxdepth 1 -type f -printf '  - %f\n' | sort
