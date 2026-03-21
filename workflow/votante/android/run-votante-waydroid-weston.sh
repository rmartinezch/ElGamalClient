#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APK_PATH="$PROJECT_ROOT/dist/android/VotanteAndroid-portable.apk"
PACKAGE_NAME="pe.gob.onpe.votodigital.votante.android"
WAYDROID_SOCKET="${WAYDROID_WESTON_SOCKET:-wayland-cifrador}"
WAYDROID_UI_LOG="$PROJECT_ROOT/.build/waydroid-weston/votante-ui.log"
SHOW_UI="${SHOW_UI:-1}"
REBUILD_APK="${REBUILD_APK:-0}"
REINSTALL_APP="${REINSTALL_APP:-1}"
START_TIMEOUT="${START_TIMEOUT:-120}"
CLEAN_START="${CLEAN_START:-1}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[votante-waydroid] ERROR: falta comando requerido: $1" >&2
    exit 1
  fi
}

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "[votante-waydroid] ERROR: falta archivo requerido: $1" >&2
    exit 1
  fi
}

match_line() {
  local pattern="$1"
  if command -v rg >/dev/null 2>&1; then
    rg -q "$pattern"
  else
    grep -Eq "$pattern"
  fi
}

waydroid_status() {
  waydroid status 2>&1 || true
}

waydroid_app_list() {
  waydroid app list 2>&1 || true
}

session_running_flag() {
  local status
  status="$(waydroid_status)"
  [[ "$status" == *$'Session:\tRUNNING'* && "$status" == *$'Container:\tRUNNING'* ]]
}

session_ready() {
  local app_list

  if ! session_running_flag; then
    return 1
  fi

  app_list="$(waydroid_app_list)"
  if printf '%s\n' "$app_list" | match_line 'WayDroid session is stopped|Failed to get service waydroidplatform'; then
    return 1
  fi

  printf '%s\n' "$app_list" | match_line '^packageName:|^Name:'
}

wait_for_session() {
  local end=$((SECONDS + START_TIMEOUT))
  while (( SECONDS < end )); do
    if session_ready; then
      return 0
    fi
    sleep 2
  done

  echo "[votante-waydroid] ERROR: Waydroid no quedo operativo dentro de ${START_TIMEOUT}s." >&2
  echo "[votante-waydroid] Estado reportado:" >&2
  waydroid_status >&2 || true
  echo "[votante-waydroid] Sonda app list:" >&2
  waydroid_app_list >&2 || true
  exit 1
}

package_installed() {
  waydroid_app_list | match_line "^packageName: ${PACKAGE_NAME}$"
}

wait_for_package() {
  local end=$((SECONDS + START_TIMEOUT))
  while (( SECONDS < end )); do
    if package_installed; then
      return 0
    fi
    sleep 2
  done

  echo "[votante-waydroid] ERROR: el paquete $PACKAGE_NAME no aparecio tras la instalacion." >&2
  exit 1
}

ensure_apk() {
  if [[ "$REBUILD_APK" == "1" || ! -f "$APK_PATH" ]]; then
    echo "[votante-waydroid] Generando APK portable del votante..."
    "$SCRIPT_DIR/build-votante-portable-apk.sh"
  fi
  require_file "$APK_PATH"
}

ensure_waydroid_weston() {
  if [[ "$CLEAN_START" != "1" ]] && session_ready; then
    echo "[votante-waydroid] Waydroid ya esta operativo."
    return 0
  fi

  if [[ "$CLEAN_START" == "1" ]]; then
    echo "[votante-waydroid] Reiniciando Waydroid sobre Weston en modo limpio..."
  else
    echo "[votante-waydroid] Iniciando Waydroid sobre Weston..."
  fi
  mkdir -p "$PROJECT_ROOT/.build/waydroid-weston"
  setsid bash -lc \
    "env SHOW_UI=0 WAYDROID_SHOW_UI=0 \"$PROJECT_ROOT/scripts/android/start-waydroid-weston.sh\"" \
    >>"$PROJECT_ROOT/.build/waydroid-weston/votante-launch-bootstrap.log" 2>&1 </dev/null &

  wait_for_session
}

install_app() {
  if [[ "$REINSTALL_APP" != "1" ]] && package_installed; then
    echo "[votante-waydroid] La app ya estaba instalada; omitiendo reinstalacion."
    return 0
  fi

  echo "[votante-waydroid] Instalando APK en Waydroid..."
  waydroid app install "$APK_PATH"
  wait_for_package
}

show_ui() {
  if [[ "$SHOW_UI" != "1" ]]; then
    return 0
  fi

  mkdir -p "$(dirname "$WAYDROID_UI_LOG")"
  pkill -u "$(id -u)" -f 'waydroid show-full-ui' >/dev/null 2>&1 || true
  nohup env WAYLAND_DISPLAY="$WAYDROID_SOCKET" XDG_SESSION_TYPE=wayland \
    waydroid show-full-ui >>"$WAYDROID_UI_LOG" 2>&1 &
}

launch_app() {
  echo "[votante-waydroid] Lanzando $PACKAGE_NAME..."
  waydroid app launch "$PACKAGE_NAME"
}

require_cmd waydroid
require_cmd nohup
require_cmd setsid

ensure_apk
ensure_waydroid_weston
install_app
show_ui
launch_app

printf '\n[votante-waydroid] APK: %s\n' "$APK_PATH"
printf '[votante-waydroid] Paquete: %s\n' "$PACKAGE_NAME"
printf '[votante-waydroid] Display Weston: %s\n' "$WAYDROID_SOCKET"
printf '[votante-waydroid] UI log: %s\n' "$WAYDROID_UI_LOG"
