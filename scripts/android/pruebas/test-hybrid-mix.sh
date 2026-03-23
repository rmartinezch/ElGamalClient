#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"
ANDROID_DIR="$(resolve_android_project_dir "$PROJECT_ROOT")"
WORK_ROOT="$PROJECT_ROOT/.build/android-hybrid-mix"
LINUX_MIX_DIR="$WORK_ROOT/linux-mix"
ANDROID_PULL_DIR="$WORK_ROOT/android"
APP_ID="pe.gob.onpe.votodigital.cifrador.android"
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="$APP_ID.AndroidCipherExportInstrumentationTest#cifraVotosRealesYExportaCiphertexts"
ADB_BIN="$(resolve_adb_bin "$PROJECT_ROOT")"
EMULATOR_PORT="${ANDROID_EMULATOR_PORT:-5556}"
DEFAULT_SERIAL="emulator-$EMULATOR_PORT"
ANDROID_SERIAL="${ANDROID_SERIAL:-$DEFAULT_SERIAL}"
VOTES_PATH="${ANDROID_TEST_VOTES_PATH:-$PROJECT_ROOT/recursos/shuffled_votes.txt}"
PUBLIC_KEY_PATH="$LINUX_MIX_DIR/publicKey"
PLAINTEXTS_PATH="$LINUX_MIX_DIR/plaintexts"
CIPHERTEXTS_EXT_PATH="$ANDROID_PULL_DIR/ciphertexts_ext"
ANDROID_LOG_PATH="$ANDROID_PULL_DIR/android-cifrador.log"
SUMMARY_PATH="$WORK_ROOT/summary.txt"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[test-hybrid-mix] ERROR: falta comando '$1'" >&2
    exit 1
  fi
}

ensure_device() {
  if "$ADB_BIN" devices | awk 'NR>1 && $2 == "device" {print $1}' | grep -qx "$ANDROID_SERIAL"; then
    return
  fi

  "$PROJECT_ROOT/scripts/android/entorno/start-emulator.sh"
  "$ADB_BIN" -s "$ANDROID_SERIAL" wait-for-device
}

copy_into_app() {
  local source_path="$1"
  local target_path="$2"

  "$ADB_BIN" -s "$ANDROID_SERIAL" shell "run-as $APP_ID mkdir -p files/inputs files/exports files/logs"
  "$ADB_BIN" -s "$ANDROID_SERIAL" shell "run-as $APP_ID sh -c 'cat > $target_path'" < "$source_path"
}

pull_from_app() {
  local source_path="$1"
  local target_path="$2"

  mkdir -p "$(dirname "$target_path")"
  "$ADB_BIN" -s "$ANDROID_SERIAL" exec-out run-as "$APP_ID" cat "$source_path" > "$target_path"
}

prepare_linux_mix_dir() {
  rm -rf "$LINUX_MIX_DIR"
  mkdir -p "$LINUX_MIX_DIR"

  (
    cd "$LINUX_MIX_DIR"
    local pgroup rand
    pgroup="$(vog -gen ECqPGroup -name "P-256")"
    vmni -prot -sid 'ONPE' -name 'Eleccion Onpe' -nopart 1 -thres 1 -pgroup "$pgroup"
    rand="$(vog -gen RandomDevice /dev/urandom)"
    vmni -party -e -name "Servidor01" -hint "localhost:4041" -http "http://localhost:8041" -rand "$rand"
    cp localProtInfo.xml protInfo01.xml
    vmni -merge protInfo01.xml protInfo.xml
    vmn -keygen -e publicKey
  )
}

run_linux_mix() {
  (
    cd "$LINUX_MIX_DIR"
    cp "$CIPHERTEXTS_EXT_PATH" ciphertexts_ext
    vmnc -ciphs -sloppy -ini native -width 1 ciphertexts_ext ciphertexts
    vmn -shuffle privInfo.xml protInfo.xml ciphertexts ciphertextsout
    vmn -decrypt privInfo.xml protInfo.xml ciphertextsout plaintexts_orig
    vmnc -plain -outi native plaintexts_orig plaintexts
    vmnv -v -e -mix protInfo.xml dir/nizkp/default
  )
}

compare_votes() {
  local original_count decrypted_count
  original_count="$(wc -l < "$VOTES_PATH" | tr -d ' ')"
  decrypted_count="$(wc -l < "$PLAINTEXTS_PATH" | tr -d ' ')"

  sort "$VOTES_PATH" > "$WORK_ROOT/original.sorted"
  sort "$PLAINTEXTS_PATH" > "$WORK_ROOT/plaintexts.sorted"

  local same_order="false"
  if cmp -s "$VOTES_PATH" "$PLAINTEXTS_PATH"; then
    same_order="true"
  fi

  if ! cmp -s "$WORK_ROOT/original.sorted" "$WORK_ROOT/plaintexts.sorted"; then
    echo "[test-hybrid-mix] ERROR: los votos descifrados no coinciden con el conjunto original." >&2
    exit 1
  fi

  if [[ "$same_order" == "true" ]]; then
    echo "[test-hybrid-mix] ERROR: los votos descifrados quedaron en el mismo orden." >&2
    exit 1
  fi

  {
    echo "android_serial=$ANDROID_SERIAL"
    echo "public_key=$PUBLIC_KEY_PATH"
    echo "ciphertexts_ext=$CIPHERTEXTS_EXT_PATH"
    echo "plaintexts=$PLAINTEXTS_PATH"
    echo "original_count=$original_count"
    echo "decrypted_count=$decrypted_count"
    echo "same_order=$same_order"
    echo "multiset_equal=true"
    echo "original_sorted_sha256=$(sha256sum "$WORK_ROOT/original.sorted" | awk '{print $1}')"
    echo "decrypted_sorted_sha256=$(sha256sum "$WORK_ROOT/plaintexts.sorted" | awk '{print $1}')"
  } > "$SUMMARY_PATH"
}

require_command vog
require_command vmni
require_command vmn
require_command vmnc
require_command vmnv
require_command "$ADB_BIN"
require_command sha256sum

rm -rf "$ANDROID_PULL_DIR"
mkdir -p "$ANDROID_PULL_DIR"

prepare_linux_mix_dir

SKIP_ANDROID_JNI="${SKIP_ANDROID_JNI:-1}" "$PROJECT_ROOT/scripts/android/compilacion/build-debug.sh"
ensure_device

(
  cd "$ANDROID_DIR"
  ./gradlew :app:installDebug :app:installDebugAndroidTest
)

copy_into_app "$PUBLIC_KEY_PATH" "files/inputs/publicKey"
copy_into_app "$VOTES_PATH" "files/inputs/shuffled_votes.txt"

"$ADB_BIN" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class "$TEST_CLASS" \
  "$TEST_RUNNER"

pull_from_app "files/exports/ciphertexts_ext" "$CIPHERTEXTS_EXT_PATH"
pull_from_app "files/logs/android-cifrador.log.0" "$ANDROID_LOG_PATH" || pull_from_app "files/logs/android-cifrador.log" "$ANDROID_LOG_PATH"

if [[ ! -s "$CIPHERTEXTS_EXT_PATH" ]]; then
  echo "[test-hybrid-mix] ERROR: Android no genero ciphertexts_ext valido." >&2
  exit 1
fi

run_linux_mix
compare_votes

echo "[test-hybrid-mix] OK"
echo "[test-hybrid-mix] Resumen: $SUMMARY_PATH"
