#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

CIFRADOR_BUILD_SCRIPT="$PROJECT_ROOT/scripts/android/compilacion/build-cifrador.sh"
TOOL_BUILD_SCRIPT="$PROJECT_ROOT/scripts/android/compilacion/build-truerng-diagnostic.sh"
VOTER_BUILD_SCRIPT="$PROJECT_ROOT/workflow/votante/android/build-votante-portable-apk.sh"
DIST_ANDROID_DIR="$PROJECT_ROOT/dist/android"
TOOL_APK_PATH="$DIST_ANDROID_DIR/TrueRNG-Diagnostico.apk"
VOTER_APK_PATH="$DIST_ANDROID_DIR/VotanteAndroid-portable.apk"

DEVICE_SERIAL="${DEVICE_SERIAL:-}"
BUILD_CIFRADOR="${BUILD_CIFRADOR:-1}"
BUILD_TOOL_ANDROID="${BUILD_TOOL_ANDROID:-1}"
BUILD_VOTER_ANDROID="${BUILD_VOTER_ANDROID:-1}"
LAUNCH_APPS="${LAUNCH_APPS:-0}"
TOOL_PACKAGE="pe.gob.onpe.votodigital.truerngdiag"
VOTER_PACKAGE="pe.gob.onpe.votodigital.votante.android"
TOOL_ACTIVITY="$TOOL_PACKAGE/.MainActivity"
VOTER_ACTIVITY="$VOTER_PACKAGE/.MainActivity"
TMP_TOOL_APK="/data/local/tmp/TrueRNG-Diagnostico.apk"
TMP_VOTER_APK="/data/local/tmp/VotanteAndroid-portable.apk"
ACTIVE_DEVICE_SERIAL=""
ORIG_PACKAGE_VERIFIER=""
ORIG_ADB_VERIFIER=""

log() {
  printf '[android-phone] %s\n' "$1"
}

fail() {
  printf '[android-phone] ERROR: %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "falta comando requerido: $1"
}

detect_device() {
  if [[ -n "$DEVICE_SERIAL" ]]; then
    printf '%s\n' "$DEVICE_SERIAL"
    return 0
  fi

  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ "${#devices[@]}" -eq 0 ]]; then
    fail "no hay dispositivos ADB en estado device"
  fi
  if [[ "${#devices[@]}" -gt 1 ]]; then
    fail "hay varios dispositivos ADB; exporte DEVICE_SERIAL=<serial>"
  fi

  printf '%s\n' "${devices[0]}"
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "falta archivo requerido: $path"
}

build_artifacts() {
  if [[ "$BUILD_CIFRADOR" == "1" ]]; then
    log "Compilando cifrador Android..."
    "$CIFRADOR_BUILD_SCRIPT"
  fi

  if [[ "$BUILD_TOOL_ANDROID" == "1" ]]; then
    log "Compilando tool/android..."
    "$TOOL_BUILD_SCRIPT"
  fi

  if [[ "$BUILD_VOTER_ANDROID" == "1" ]]; then
    log "Compilando estacion Android..."
    "$VOTER_BUILD_SCRIPT"
  fi

  require_file "$TOOL_APK_PATH"
  require_file "$VOTER_APK_PATH"
}

remember_setting() {
  local device="$1"
  local key="$2"
  adb -s "$device" shell settings get global "$key" | tr -d '\r'
}

restore_setting() {
  local device="$1"
  local key="$2"
  local value="$3"

  if [[ -n "$value" && "$value" != "null" ]]; then
    adb -s "$device" shell settings put global "$key" "$value" >/dev/null
  else
    adb -s "$device" shell settings delete global "$key" >/dev/null || true
  fi
}

restore_verifier_settings() {
  if [[ -z "$ACTIVE_DEVICE_SERIAL" ]]; then
    return 0
  fi

  restore_setting "$ACTIVE_DEVICE_SERIAL" package_verifier_enable "$ORIG_PACKAGE_VERIFIER"
  restore_setting "$ACTIVE_DEVICE_SERIAL" verifier_verify_adb_installs "$ORIG_ADB_VERIFIER"
}

install_via_pm() {
  local device="$1"
  local local_apk="$2"
  local remote_apk="$3"

  adb -s "$device" push "$local_apk" "$remote_apk" >/dev/null
  adb -s "$device" shell pm install -r "$remote_apk"
}

launch_apps() {
  local device="$1"

  [[ "$LAUNCH_APPS" == "1" ]] || return 0

  log "Lanzando aplicaciones..."
  adb -s "$device" shell am start -n "$TOOL_ACTIVITY" >/dev/null
  adb -s "$device" shell am start -n "$VOTER_ACTIVITY" >/dev/null
}

report_versions() {
  local device="$1"

  log "Versiones instaladas:"
  adb -s "$device" shell dumpsys package "$TOOL_PACKAGE" | grep -E 'versionName=|lastUpdateTime=' | tr -d '\r'
  adb -s "$device" shell dumpsys package "$VOTER_PACKAGE" | grep -E 'versionName=|lastUpdateTime=' | tr -d '\r'
}

main() {
  require_cmd adb

  local device
  device="$(detect_device)"
  ACTIVE_DEVICE_SERIAL="$device"
  log "Dispositivo objetivo: $device"

  build_artifacts

  adb -s "$device" wait-for-device >/dev/null

  ORIG_PACKAGE_VERIFIER="$(remember_setting "$device" package_verifier_enable)"
  ORIG_ADB_VERIFIER="$(remember_setting "$device" verifier_verify_adb_installs)"

  trap restore_verifier_settings EXIT

  log "Deshabilitando temporalmente la verificacion ADB..."
  adb -s "$device" shell settings put global package_verifier_enable 0 >/dev/null
  adb -s "$device" shell settings put global verifier_verify_adb_installs 0 >/dev/null

  log "Instalando TrueRNG Diagnostico..."
  install_via_pm "$device" "$TOOL_APK_PATH" "$TMP_TOOL_APK"

  log "Instalando Votante Android..."
  install_via_pm "$device" "$VOTER_APK_PATH" "$TMP_VOTER_APK"

  adb -s "$device" shell rm -f "$TMP_TOOL_APK" "$TMP_VOTER_APK" >/dev/null || true

  launch_apps "$device"
  report_versions "$device"

  log "Proceso completado."
}

main "$@"
