#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
LOG_DIR="$PROJECT_ROOT/.build/waydroid-weston"
SOCKET_NAME="${WAYDROID_WESTON_SOCKET:-wayland-cifrador}"
PID_FILE="$LOG_DIR/weston.pid"
WESTON_LOG="$LOG_DIR/weston.log"
WESTON_STDOUT="$LOG_DIR/weston.stdout.log"
SHOW_UI="${WAYDROID_SHOW_UI:-1}"
START_TIMEOUT="${WAYDROID_START_TIMEOUT:-120}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[waydroid-weston] ERROR: falta comando requerido: $1" >&2
    exit 1
  fi
}

resolve_weston_backend() {
  if [[ -n "${WESTON_BACKEND:-}" ]]; then
    printf '%s\n' "$WESTON_BACKEND"
    return 0
  fi

  if [[ -n "${DISPLAY:-}" ]]; then
    printf '%s\n' "x11-backend.so"
    return 0
  fi

  if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    printf '%s\n' "wayland-backend.so"
    return 0
  fi

  echo "[waydroid-weston] ERROR: no se pudo inferir backend de weston. Defina WESTON_BACKEND." >&2
  exit 1
}

stop_waydroid_root() {
  pkexec bash -lc '
    set -euo pipefail
    waydroid session stop >/dev/null 2>&1 || true
    waydroid container stop >/dev/null 2>&1 || true
    systemctl stop waydroid-container >/dev/null 2>&1 || true
  '
}

stop_user_processes() {
  pkill -u "$(id -u)" -x weston >/dev/null 2>&1 || true
  pkill -u "$(id -u)" -x weston-keyboard >/dev/null 2>&1 || true
  pkill -u "$(id -u)" -x weston-desktop-shell >/dev/null 2>&1 || true
  pkill -u "$(id -u)" -f 'waydroid show-full-ui' >/dev/null 2>&1 || true
  pkill -u "$(id -u)" -f '/usr/bin/python3 /usr/bin/waydroid session start' >/dev/null 2>&1 || true
}

wait_until_gone() {
  local timeout="${1:-30}"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    if ! pgrep -u "$(id -u)" -x weston >/dev/null 2>&1 \
      && ! pgrep -u "$(id -u)" -f 'waydroid show-full-ui' >/dev/null 2>&1 \
      && ! pgrep -u "$(id -u)" -f '/usr/bin/python3 /usr/bin/waydroid session start' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "[waydroid-weston] ERROR: quedaron procesos previos de weston o waydroid." >&2
  exit 1
}

wait_for_socket() {
  local socket_path="$1"
  local end=$((SECONDS + START_TIMEOUT))
  while (( SECONDS < end )); do
    if [[ -S "$socket_path" ]]; then
      return 0
    fi
    sleep 1
  done

  echo "[waydroid-weston] ERROR: no aparecio el socket $socket_path." >&2
  exit 1
}

wait_for_boot() {
  local end=$((SECONDS + START_TIMEOUT))
  while (( SECONDS < end )); do
    local boot_completed
    boot_completed="$(pkexec bash -lc 'waydroid shell getprop sys.boot_completed 2>/dev/null || true' | tr -d '\r')"
    if [[ "$boot_completed" == "1" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "[waydroid-weston] ERROR: Android no completo el arranque en ${START_TIMEOUT}s." >&2
  exit 1
}

require_cmd weston
require_cmd waydroid
require_cmd pkexec

WESTON_BACKEND_RESOLVED="$(resolve_weston_backend)"
SOCKET_PATH="$RUNTIME_DIR/$SOCKET_NAME"

mkdir -p "$LOG_DIR"

echo "[waydroid-weston] Limpiando instancias previas..."
stop_waydroid_root
stop_user_processes
rm -f "$SOCKET_PATH" "$SOCKET_PATH.lock" "$PID_FILE"
wait_until_gone

echo "[waydroid-weston] Iniciando weston con backend $WESTON_BACKEND_RESOLVED..."
nohup weston \
  --backend="$WESTON_BACKEND_RESOLVED" \
  --socket="$SOCKET_NAME" \
  --idle-time=0 \
  --log="$WESTON_LOG" \
  >"$WESTON_STDOUT" 2>&1 &
echo $! > "$PID_FILE"

wait_for_socket "$SOCKET_PATH"

echo "[waydroid-weston] Iniciando sesion de Waydroid..."
if [[ "$SHOW_UI" == "1" ]]; then
  (
    wait_for_boot
    echo "[waydroid-weston] Abriendo UI de Waydroid..."
    nohup env WAYLAND_DISPLAY="$SOCKET_NAME" XDG_SESSION_TYPE=wayland \
      waydroid show-full-ui \
      >>"$LOG_DIR/waydroid-ui.log" 2>&1 &
  ) &
fi

echo "[waydroid-weston] Socket: $SOCKET_PATH"
echo "[waydroid-weston] Weston log: $WESTON_LOG"
echo "[waydroid-weston] Weston pid file: $PID_FILE"
echo "[waydroid-weston] Manteniendo la sesion en foreground; use Ctrl+C para salir."

exec env WAYLAND_DISPLAY="$SOCKET_NAME" XDG_SESSION_TYPE=wayland \
  waydroid session start
