#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${HOST:-0.0.0.0}"
detect_public_host() {
  local candidate
  candidate="$(hostname -I 2>/dev/null | awk '{print $1}')"
  if [[ -n "${candidate}" ]]; then
    printf '%s\n' "${candidate}"
  else
    hostname
  fi
}
PUBLIC_HOST="${PUBLIC_HOST:-$(detect_public_host)}"
BASE_PORT="${1:-7040}"
URL="http://${PUBLIC_HOST}:${BASE_PORT}"
export VERIFICATUM_GUI_CLEAN_START="${VERIFICATUM_GUI_CLEAN_START:-1}"

stop_previous_instances() {
  local start_port end_port port
  start_port="${BASE_PORT}"
  end_port="$((BASE_PORT + 9))"

  for ((port = start_port; port <= end_port; port++)); do
    if fuser "${port}/tcp" >/dev/null 2>&1; then
      echo "Cerrando proceso anterior en el puerto ${port}"
      fuser -k "${port}/tcp" >/dev/null 2>&1 || true
    fi
  done

  sleep 1
}

cd "$SCRIPT_DIR"
stop_previous_instances
echo "Iniciando panel de control en ${URL}"
echo "Escuchando en ${HOST}:${BASE_PORT}"
echo "Ventanas de parties publicadas en ${PUBLIC_HOST}:$((BASE_PORT + 1))..$((BASE_PORT + 9))"
echo "Arranque limpio: VERIFICATUM_GUI_CLEAN_START=${VERIFICATUM_GUI_CLEAN_START}"

if command -v xdg-open >/dev/null 2>&1; then
  (sleep 1; xdg-open "$URL" >/dev/null 2>&1 || true) &
fi

exec python3 server.py --host "$HOST" --public-host "$PUBLIC_HOST" --base-port "$BASE_PORT"
