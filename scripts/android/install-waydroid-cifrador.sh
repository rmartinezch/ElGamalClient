#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ARTIFACT_ROOT="$PROJECT_ROOT/dist/android/image/Cifrador"
APK_PATH="$ARTIFACT_ROOT/apk/Cifrador.apk"
TEST_APK_PATH="$ARTIFACT_ROOT/apk/pruebas_auto.apk"
ZIP_PATH="$PROJECT_ROOT/dist/android/Cifrador-1.1.0-android-portable.zip"
PUBLIC_KEY_PATH="$ARTIFACT_ROOT/recursos/publicKey"
VOTES_PATH="$ARTIFACT_ROOT/recursos/shuffled_votes.txt"
HOST_WORK_DIR="$PROJECT_ROOT/.build/waydroid-cifrador"
WAYDROID_DIR="/sdcard/Download/Cifrador"
APP_ID="pe.gob.onpe.votodigital.cifrador.android"
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="$APP_ID.AndroidCipherExportInstrumentationTest#cifraVotosRealesYExportaCiphertexts"
TEXT_VIEWER_PACKAGE="${TEXT_VIEWER_PACKAGE:-com.maxistar.textpad}"
TEXT_VIEWER_APK_URL="${TEXT_VIEWER_APK_URL:-https://f-droid.org/repo/com.maxistar.textpad_56.apk}"
TEXT_VIEWER_APK_PATH="$HOST_WORK_DIR/SimpleTextEditor.apk"
BUILD_ARTIFACTS="${BUILD_ARTIFACTS:-1}"
SKIP_ANDROID_JNI="${SKIP_ANDROID_JNI:-1}"
SHOW_APP="${SHOW_APP:-1}"
INSTALL_TEXT_VIEWER="${INSTALL_TEXT_VIEWER:-1}"
AUTOTEST_LOG="$HOST_WORK_DIR/autoprueba-instrumentation.log"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[waydroid-cifrador] ERROR: falta comando requerido: $1" >&2
    exit 1
  fi
}

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "[waydroid-cifrador] ERROR: falta archivo requerido: $1" >&2
    exit 1
  fi
}

ensure_waydroid_running() {
  local status
  status="$(waydroid status 2>&1 || true)"
  if [[ "$status" != *$'Session:\tRUNNING'* ]]; then
    echo "[waydroid-cifrador] ERROR: Waydroid no esta en ejecucion." >&2
    echo "[waydroid-cifrador] Inicie primero ./scripts/android/start-waydroid-weston.sh" >&2
    exit 1
  fi

  if [[ "$status" == *$'Container:\tFROZEN'* ]]; then
    waydroid app launch com.android.documentsui >/dev/null 2>&1 || true
    sleep 2
  fi
}

ensure_artifacts() {
  if [[ "$BUILD_ARTIFACTS" == "1" ]]; then
    (
      cd "$PROJECT_ROOT"
      REBUILD_IMAGE=1 SKIP_ANDROID_JNI="$SKIP_ANDROID_JNI" \
        "$PROJECT_ROOT/scripts/android/package-cifrador-portable.sh"
    )
  fi

  require_file "$APK_PATH"
  require_file "$TEST_APK_PATH"
  require_file "$ZIP_PATH"
  require_file "$PUBLIC_KEY_PATH"
  require_file "$VOTES_PATH"
}

ensure_text_viewer_apk() {
  if [[ "$INSTALL_TEXT_VIEWER" != "1" ]]; then
    return 0
  fi

  require_cmd curl
  mkdir -p "$HOST_WORK_DIR"
  curl -fsSL "$TEXT_VIEWER_APK_URL" -o "$TEXT_VIEWER_APK_PATH"
  require_file "$TEXT_VIEWER_APK_PATH"
}

run_root_phase() {
  pkexec env \
    APK_PATH="$APK_PATH" \
    TEST_APK_PATH="$TEST_APK_PATH" \
    ZIP_PATH="$ZIP_PATH" \
    PUBLIC_KEY_PATH="$PUBLIC_KEY_PATH" \
    VOTES_PATH="$VOTES_PATH" \
    HOST_WORK_DIR="$HOST_WORK_DIR" \
    WAYDROID_DIR="$WAYDROID_DIR" \
    APP_ID="$APP_ID" \
    TEST_RUNNER="$TEST_RUNNER" \
    TEST_CLASS="$TEST_CLASS" \
    TEXT_VIEWER_PACKAGE="$TEXT_VIEWER_PACKAGE" \
    TEXT_VIEWER_APK_PATH="$TEXT_VIEWER_APK_PATH" \
    INSTALL_TEXT_VIEWER="$INSTALL_TEXT_VIEWER" \
    AUTOTEST_LOG="$AUTOTEST_LOG" \
    HOST_UID="$HOST_UID" \
    HOST_GID="$HOST_GID" \
    bash <<'EOF'
set -euo pipefail

APP_DATA_DIR="/data/user/0/$APP_ID"

copy_host_file_to_waydroid() {
  local source_path="$1"
  local target_path="$2"
  cat "$source_path" | waydroid shell -- sh -c "cat > $target_path"
}

copy_waydroid_file_to_host() {
  local source_path="$1"
  local target_path="$2"
  local temp_path="${target_path}.tmp"
  rm -f "$temp_path"
  waydroid shell -- cat "$source_path" > "$temp_path"
  mv "$temp_path" "$target_path"
}

copy_host_file_into_app() {
  local source_path="$1"
  local target_dir="$2"
  local target_path="$3"
  cat "$source_path" | waydroid shell -- run-as "$APP_ID" sh -c "mkdir -p $target_dir && cat > $target_path"
}

copy_app_file_to_host() {
  local source_path="$1"
  local target_path="$2"
  local temp_path="${target_path}.tmp"
  rm -f "$temp_path"
  waydroid shell -- cat "$APP_DATA_DIR/$source_path" > "$temp_path"
  mv "$temp_path" "$target_path"
}

wait_for_boot() {
  local end=$((SECONDS + 90))
  while (( SECONDS < end )); do
    local boot_completed
    boot_completed="$(timeout 10 waydroid shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot_completed" == "1" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "[waydroid-cifrador] ERROR: Android no termino de bootear." >&2
  exit 1
}

install_apks() {
  waydroid shell -- mkdir -p "$WAYDROID_DIR"
  copy_host_file_to_waydroid "$APK_PATH" "$WAYDROID_DIR/Cifrador.apk"
  copy_host_file_to_waydroid "$TEST_APK_PATH" "$WAYDROID_DIR/pruebas_auto.apk"
  copy_host_file_to_waydroid "$ZIP_PATH" "$WAYDROID_DIR/Cifrador-1.1.0-android-portable.zip"
  if [[ "$INSTALL_TEXT_VIEWER" == "1" ]]; then
    copy_host_file_to_waydroid "$TEXT_VIEWER_APK_PATH" "$WAYDROID_DIR/SimpleTextEditor.apk"
  fi
  copy_host_file_to_waydroid "$PUBLIC_KEY_PATH" "$WAYDROID_DIR/publicKey"
  copy_host_file_to_waydroid "$VOTES_PATH" "$WAYDROID_DIR/shuffled_votes.txt"
  waydroid shell -- pm install -r "$WAYDROID_DIR/Cifrador.apk"
  waydroid shell -- pm install -r -t "$WAYDROID_DIR/pruebas_auto.apk"
  if [[ "$INSTALL_TEXT_VIEWER" == "1" ]]; then
    waydroid shell -- pm install -r "$WAYDROID_DIR/SimpleTextEditor.apk"
  fi
  waydroid shell -- ls -lah "$WAYDROID_DIR"
}

prepare_autotest_inputs() {
  local loose_public_key="$HOST_WORK_DIR/publicKey.from-waydroid"
  local loose_votes="$HOST_WORK_DIR/shuffled_votes.from-waydroid"

  copy_waydroid_file_to_host "$WAYDROID_DIR/publicKey" "$loose_public_key"
  copy_waydroid_file_to_host "$WAYDROID_DIR/shuffled_votes.txt" "$loose_votes"
  copy_host_file_into_app "$loose_public_key" "files/inputs" "files/inputs/publicKey"
  copy_host_file_into_app "$loose_votes" "files/inputs" "files/inputs/shuffled_votes.txt"
}

run_autotest() {
  local status=0

  if ! waydroid shell -- am instrument -w -r \
      -e class "$TEST_CLASS" \
      "$TEST_RUNNER" | tee "$AUTOTEST_LOG"; then
    status=$?
  fi

  if grep -q 'FAILURES!!!' "$AUTOTEST_LOG" \
    || grep -q '^INSTRUMENTATION_STATUS_CODE: -2$' "$AUTOTEST_LOG" \
    || ! grep -q '^OK (1 test)$' "$AUTOTEST_LOG"; then
    status=1
  fi

  return "$status"
}

expose_autotest_outputs() {
  local host_ciphertexts="$HOST_WORK_DIR/ciphertexts_ext"
  local host_log="$HOST_WORK_DIR/android-cifrador.log"

  if copy_app_file_to_host "files/exports/ciphertexts_ext" "$host_ciphertexts" 2>/dev/null; then
    copy_host_file_to_waydroid "$host_ciphertexts" "$WAYDROID_DIR/ciphertexts_ext"
  fi

  if ! copy_app_file_to_host "files/logs/android-cifrador.log.0" "$host_log" 2>/dev/null; then
    copy_app_file_to_host "files/logs/android-cifrador.log" "$host_log" 2>/dev/null || true
  fi

  if [[ -f "$host_log" ]]; then
    copy_host_file_to_waydroid "$host_log" "$WAYDROID_DIR/android-cifrador.log"
  fi

  copy_host_file_to_waydroid "$AUTOTEST_LOG" "$WAYDROID_DIR/autoprueba-instrumentation.log"
}

mkdir -p "$HOST_WORK_DIR"
wait_for_boot
install_apks
prepare_autotest_inputs

autotest_status=0
if run_autotest; then
  autotest_status=0
else
  autotest_status=$?
fi

expose_autotest_outputs || true
chown -R "$HOST_UID:$HOST_GID" "$HOST_WORK_DIR"
exit "$autotest_status"
EOF
}

launch_app() {
  if [[ "$SHOW_APP" == "1" ]]; then
    waydroid app launch "$APP_ID" >/dev/null 2>&1 || true
  fi
}

require_cmd waydroid
require_cmd pkexec
mkdir -p "$HOST_WORK_DIR"

ensure_waydroid_running
ensure_artifacts
ensure_text_viewer_apk

echo "[waydroid-cifrador] Instalando app y ejecutando autoprueba con archivos sueltos..."
if run_root_phase; then
  launch_app
  echo "[waydroid-cifrador] OK"
  echo "[waydroid-cifrador] Manuales visibles en: $WAYDROID_DIR"
  echo "[waydroid-cifrador] Log de autoprueba: $AUTOTEST_LOG"
  if [[ "$INSTALL_TEXT_VIEWER" == "1" ]]; then
    echo "[waydroid-cifrador] Visor TXT instalado: $TEXT_VIEWER_PACKAGE"
  fi
else
  launch_app
  echo "[waydroid-cifrador] App y archivos quedaron instalados para prueba manual." >&2
  echo "[waydroid-cifrador] Log de autoprueba: $AUTOTEST_LOG" >&2
  exit 1
fi
