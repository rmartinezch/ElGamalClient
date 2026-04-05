#!/usr/bin/env bash
# ============================================================================
# autoprueba-votante-ios.sh
#
# Autoprueba integral de la estacion de voto iOS:
#   1. Ejecuta el smoke test backend (cifrado + mock mixer via bridge macOS)
#   2. Levanta la estacion iOS con mock mixer
#   3. Arranca un simulador iPhone y abre Safari en la URL de la estacion
#   4. Valida endpoints desde el host y desde el simulador (via simctl openurl)
#   5. Cierra simulador y servidores al terminar
#
# Requiere: Xcode con simuladores iOS instalados (xcrun simctl)
# ============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_VERSION="${APP_VERSION:-1.1.0}"
STATION_PORT="${STATION_PORT:-8795}"
MOCK_PORT="${MOCK_PORT:-7045}"
OPEN_SIMULATOR="${OPEN_SIMULATOR:-1}"
TIMEOUT_HEALTH="${TIMEOUT_HEALTH:-15}"
TIMEOUT_BOOT="${TIMEOUT_BOOT:-60}"
SIMULATOR_DEVICE="${SIMULATOR_DEVICE:-}"

TOOLS_DIR="$PROJECT_ROOT/.tools"
if [[ -d "$TOOLS_DIR/jdk-21/bin" ]]; then
  export JAVA_HOME="$TOOLS_DIR/jdk-21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

resolve_macos_classifier() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) echo "macos-x64" ;;
    arm64|aarch64) echo "macos-arm64" ;;
    *) echo "macos-${arch}" ;;
  esac
}

PIDS_TO_KILL=()
SIMULATOR_UDID=""
SIMULATOR_BOOTED_BY_US=0

cleanup() {
  for pid in "${PIDS_TO_KILL[@]+${PIDS_TO_KILL[@]}}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  if [[ "$SIMULATOR_BOOTED_BY_US" -eq 1 && -n "$SIMULATOR_UDID" ]]; then
    printf '  Apagando simulador %s ...\n' "$SIMULATOR_UDID"
    xcrun simctl shutdown "$SIMULATOR_UDID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

PASS=0
FAIL=0
TESTS=()

report_pass() {
  PASS=$((PASS + 1))
  TESTS+=("[PASS] $1")
  printf '  [PASS] %s\n' "$1"
}

report_fail() {
  FAIL=$((FAIL + 1))
  TESTS+=("[FAIL] $1: $2")
  printf '  [FAIL] %s: %s\n' "$1" "$2"
}

# ---------- Pre-requisito: Xcode y simctl --------------------------------
printf '=== Autoprueba Votante iOS (simulador) ===\n\n'

if ! command -v xcrun >/dev/null 2>&1; then
  printf '[ERROR] xcrun no encontrado. Instale Xcode para continuar.\n'
  exit 1
fi

if ! xcrun simctl list devices >/dev/null 2>&1; then
  printf '[ERROR] simctl no disponible. Verifique que Xcode este instalado y la licencia aceptada.\n'
  printf '        sudo xcodebuild -license accept\n'
  exit 1
fi

# ---------- Fase 1: Smoke test backend -----------------------------------
printf -- '--- Fase 1: Smoke test backend (cifrado iOS bridge) ---\n'
if SMOKE_PORT=8796 MOCK_PORT=7046 bash "$PROJECT_ROOT/workflow/votante/ios/test-votante-ios-smoke.sh" > /tmp/autoprueba-ios-smoke.log 2>&1; then
  report_pass "Smoke test backend iOS"
else
  EC=$?
  report_fail "Smoke test backend iOS" "exit code $EC — ver /tmp/autoprueba-ios-smoke.log"
fi

lsof -ti:7046 2>/dev/null | xargs kill -9 2>/dev/null || true
sleep 1

# ---------- Fase 2: Seleccionar y arrancar simulador ---------------------
printf '\n--- Fase 2: Simulador iOS ---\n'

if [[ -z "$SIMULATOR_DEVICE" ]]; then
  # Buscar primer iPhone disponible
  SIMULATOR_UDID=$(xcrun simctl list devices available -j \
    | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    if 'iOS' not in runtime:
        continue
    for d in devices:
        if 'iPhone' in d.get('name','') and d.get('isAvailable', False):
            print(d['udid'])
            sys.exit(0)
sys.exit(1)
" 2>/dev/null || echo "")
  if [[ -z "$SIMULATOR_UDID" ]]; then
    report_fail "Simulador iPhone" "no se encontro ningun iPhone disponible"
    printf '\n=== Resumen autoprueba iOS ===\n'
    for t in "${TESTS[@]}"; do printf '  %s\n' "$t"; done
    printf '\nTotal: %d pasaron, %d fallaron (de %d)\n' "$PASS" "$FAIL" "$((PASS + FAIL))"
    exit 1
  fi
else
  SIMULATOR_UDID="$SIMULATOR_DEVICE"
fi

SIM_NAME=$(xcrun simctl list devices -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    for d in devices:
        if d.get('udid') == '$SIMULATOR_UDID':
            print(d.get('name', 'Desconocido'))
            sys.exit(0)
print('Desconocido')
" 2>/dev/null || echo "Desconocido")

printf '  Simulador seleccionado: %s (%s)\n' "$SIM_NAME" "$SIMULATOR_UDID"

# Verificar si ya esta booted
SIM_STATE=$(xcrun simctl list devices -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    for d in devices:
        if d.get('udid') == '$SIMULATOR_UDID':
            print(d.get('state', 'Shutdown'))
            sys.exit(0)
print('Shutdown')
" 2>/dev/null || echo "Shutdown")

if [[ "$SIM_STATE" == "Booted" ]]; then
  printf '  Simulador ya esta arrancado.\n'
  report_pass "Simulador arrancado ($SIM_NAME)"
else
  printf '  Arrancando simulador (timeout %ds)...\n' "$TIMEOUT_BOOT"
  xcrun simctl boot "$SIMULATOR_UDID" 2>/dev/null || true
  SIMULATOR_BOOTED_BY_US=1

  # Esperar a que arranque
  BOOT_OK=0
  for i in $(seq 1 "$TIMEOUT_BOOT"); do
    CURRENT_STATE=$(xcrun simctl list devices -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    for d in devices:
        if d.get('udid') == '$SIMULATOR_UDID':
            print(d.get('state', 'Shutdown'))
            sys.exit(0)
print('Shutdown')
" 2>/dev/null || echo "Shutdown")
    if [[ "$CURRENT_STATE" == "Booted" ]]; then
      BOOT_OK=1
      break
    fi
    sleep 1
  done

  if [[ "$BOOT_OK" -eq 1 ]]; then
    report_pass "Simulador arrancado ($SIM_NAME)"
    # Abrir la app Simulator.app para que sea visible
    open -a Simulator 2>/dev/null || true
    sleep 3
  else
    report_fail "Simulador arrancado ($SIM_NAME)" "timeout ${TIMEOUT_BOOT}s"
  fi
fi

# ---------- Fase 3: Levantar estacion con mock mixer ---------------------
printf '\n--- Fase 3: Estacion iOS con mock mixer ---\n'

python3 "$PROJECT_ROOT/workflow/votante/macos/mock_mixer_server.py" --host 127.0.0.1 --port "$MOCK_PORT" &
MOCK_PID=$!
PIDS_TO_KILL+=("$MOCK_PID")
sleep 1

SOURCE_DIR="$PROJECT_ROOT/workflow/votante/windows/app/src"
CLASSES_DIR="$PROJECT_ROOT/.build/votante-ios-autoprueba/classes"
MAIN_CLASS="pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"

mkdir -p "$CLASSES_DIR"
find "$SOURCE_DIR" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$CLASSES_DIR"

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
java \
  -Dvotante.root="$PROJECT_ROOT" \
  -Dvotante.station.id=ios-autoprueba \
  -Dvotante.station.bindHost=127.0.0.1 \
  -Dvotante.station.port="$STATION_PORT" \
  -Dvotante.station.serviceBaseUrl="http://127.0.0.1:$MOCK_PORT" \
  -Dvotante.station.publicDir="$PROJECT_ROOT/workflow/votante/windows/app/public" \
  -Dvotante.station.cifradorDir="$PROJECT_ROOT/dist/ios/library/Cifrador" \
  -Dvotante.station.cifradorJarName="ElGamalCipher-${APP_VERSION}.jar" \
  -Dvotante.station.cifradorNativeSubdir="$NATIVE_CLASSIFIER" \
  -Dvotante.station.javaBinName=java \
  -cp "$CLASSES_DIR" "$MAIN_CLASS" &
SERVER_PID=$!
PIDS_TO_KILL+=("$SERVER_PID")

# Esperar que la estacion responda
printf '  Esperando estacion en http://127.0.0.1:%s ...\n' "$STATION_PORT"
HEALTH_OK=0
for i in $(seq 1 "$TIMEOUT_HEALTH"); do
  if curl -sf "http://127.0.0.1:${STATION_PORT}/api/health" > /dev/null 2>&1; then
    HEALTH_OK=1
    break
  fi
  sleep 1
done

if [[ "$HEALTH_OK" -eq 1 ]]; then
  report_pass "Estacion iOS responde /api/health"
else
  report_fail "Estacion iOS responde /api/health" "timeout ${TIMEOUT_HEALTH}s"
fi

# ---------- Fase 4: Validaciones programaticas ---------------------------
printf '\n--- Fase 4: Validaciones programaticas ---\n'

# /api/health
HEALTH_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/api/health" 2>/dev/null || echo "")
if echo "$HEALTH_BODY" | grep -q '"status".*"UP"'; then
  report_pass "GET /api/health retorna UP"
else
  report_fail "GET /api/health retorna UP" "body: $HEALTH_BODY"
fi

# / (HTML)
INDEX_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/" 2>/dev/null || echo "")
if echo "$INDEX_BODY" | grep -q 'Padron Electoral'; then
  report_pass "GET / retorna HTML de la cabina"
else
  report_fail "GET / retorna HTML de la cabina" "no se encontro marcador"
fi

# /api/service/emission-context
EC_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/api/service/emission-context" 2>/dev/null || echo "")
if echo "$EC_BODY" | grep -q 'session_id'; then
  report_pass "GET /api/service/emission-context conecta con mixer"
else
  report_fail "GET /api/service/emission-context" "body: $EC_BODY"
fi

# /api/ballot/preview
BALLOT="districtCode=02&presidentialParty=03&senatorsNationalParty=04&senatorsNationalPv1=01&senatorsNationalPv2=02&senatorsRegionalParty=04&senatorsRegionalPv1=01&deputiesParty=03&deputiesPv1=01&deputiesPv2=04&andeanParty=05&andeanPv1=03&andeanPv2=04"
PREVIEW_BODY=$(curl -sf -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "$BALLOT" "http://127.0.0.1:${STATION_PORT}/api/ballot/preview" 2>/dev/null || echo "")
if echo "$PREVIEW_BODY" | grep -q '"lineCount"'; then
  report_pass "POST /api/ballot/preview retorna cedula"
else
  report_fail "POST /api/ballot/preview retorna cedula" "body: $PREVIEW_BODY"
fi

# /api/ballot/submit (cifrado real)
SUBMIT_BODY=$(curl -sf -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "$BALLOT" "http://127.0.0.1:${STATION_PORT}/api/ballot/submit" 2>/dev/null || echo "")
if echo "$SUBMIT_BODY" | grep -q 'receiptAccepted.*true'; then
  report_pass "POST /api/ballot/submit cifra y recibe recibo aceptado"
else
  report_fail "POST /api/ballot/submit cifra y recibe recibo" "body truncado: $(echo "$SUBMIT_BODY" | head -c 200)"
fi

# ---------- Fase 5: Abrir Safari en simulador ----------------------------
if [[ "$OPEN_SIMULATOR" == "1" && "$HEALTH_OK" -eq 1 ]]; then
  printf '\n--- Fase 5: Abriendo Safari en simulador iOS ---\n'
  STATION_URL="http://127.0.0.1:${STATION_PORT}"
  if xcrun simctl openurl "$SIMULATOR_UDID" "$STATION_URL" 2>/dev/null; then
    report_pass "Safari abierto en simulador ($SIM_NAME)"
  else
    report_fail "Safari abierto en simulador" "xcrun simctl openurl fallo"
  fi
  printf '  La estacion esta activa en el simulador. Presione Enter para cerrar...\n'
  read -r
fi

# ---------- Resumen ------------------------------------------------------
printf '\n=== Resumen autoprueba iOS ===\n'
for t in "${TESTS[@]}"; do
  printf '  %s\n' "$t"
done
printf '\nTotal: %d pasaron, %d fallaron (de %d)\n' "$PASS" "$FAIL" "$((PASS + FAIL))"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
