#!/usr/bin/env bash
# ============================================================================
# autoprueba-votante-macos.sh
#
# Autoprueba integral de la estacion de voto macOS:
#   1. Ejecuta el smoke test backend (cifrado + mock mixer)
#   2. Levanta la estacion real con mock mixer
#   3. Abre Safari en la UI de la estacion
#   4. Valida que la UI responda con /api/health
#   5. Cierra todo al terminar
# ============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_VERSION="${APP_VERSION:-1.1.0}"
STATION_PORT="${STATION_PORT:-8793}"
MOCK_PORT="${MOCK_PORT:-7043}"
OPEN_BROWSER="${OPEN_BROWSER:-1}"
TIMEOUT_HEALTH="${TIMEOUT_HEALTH:-15}"

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
cleanup() {
  for pid in "${PIDS_TO_KILL[@]+${PIDS_TO_KILL[@]}}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
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

printf '=== Autoprueba Votante macOS ===\n\n'

# ---------- Fase 1: Smoke test backend ------------------------------------
printf -- '--- Fase 1: Smoke test backend (cifrado + mock mixer) ---\n'
if SMOKE_PORT=8794 MOCK_PORT=7044 bash "$PROJECT_ROOT/workflow/votante/macos/test-votante-macos-smoke.sh" > /tmp/autoprueba-macos-smoke.log 2>&1; then
  report_pass "Smoke test backend"
else
  EC=$?
  report_fail "Smoke test backend" "exit code $EC"
  printf '  Log: /tmp/autoprueba-macos-smoke.log\n'
fi

# Limpiar puertos del smoke test
lsof -ti:7044 2>/dev/null | xargs kill -9 2>/dev/null || true
sleep 1

# ---------- Fase 2: Levantar estacion con mock mixer ---------------------
printf '\n--- Fase 2: Estacion real con mock mixer ---\n'

python3 "$PROJECT_ROOT/workflow/votante/macos/mock_mixer_server.py" --host 127.0.0.1 --port "$MOCK_PORT" &
MOCK_PID=$!
PIDS_TO_KILL+=("$MOCK_PID")
sleep 1

SOURCE_DIR="$PROJECT_ROOT/workflow/votante/windows/app/src"
CLASSES_DIR="$PROJECT_ROOT/.build/votante-macos-autoprueba/classes"
MAIN_CLASS="pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"

mkdir -p "$CLASSES_DIR"
find "$SOURCE_DIR" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$CLASSES_DIR"

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
java \
  -Dvotante.root="$PROJECT_ROOT" \
  -Dvotante.station.id=macos-autoprueba \
  -Dvotante.station.bindHost=127.0.0.1 \
  -Dvotante.station.port="$STATION_PORT" \
  -Dvotante.station.serviceBaseUrl="http://127.0.0.1:$MOCK_PORT" \
  -Dvotante.station.publicDir="$PROJECT_ROOT/workflow/votante/windows/app/public" \
  -Dvotante.station.cifradorDir="$PROJECT_ROOT/dist/macos/image/Cifrador" \
  -Dvotante.station.cifradorJarName="ElGamalCipher-${APP_VERSION}.jar" \
  -Dvotante.station.cifradorNativeSubdir="$NATIVE_CLASSIFIER" \
  -Dvotante.station.javaBinName=java \
  -cp "$CLASSES_DIR" "$MAIN_CLASS" &
SERVER_PID=$!
PIDS_TO_KILL+=("$SERVER_PID")

# ---------- Fase 3: Esperar que la estacion responda ---------------------
printf '  Esperando que la estacion responda en http://127.0.0.1:%s ...\n' "$STATION_PORT"
HEALTH_OK=0
for i in $(seq 1 "$TIMEOUT_HEALTH"); do
  if curl -sf "http://127.0.0.1:${STATION_PORT}/api/health" > /dev/null 2>&1; then
    HEALTH_OK=1
    break
  fi
  sleep 1
done

if [[ "$HEALTH_OK" -eq 1 ]]; then
  report_pass "Estacion responde /api/health"
else
  report_fail "Estacion responde /api/health" "timeout ${TIMEOUT_HEALTH}s"
fi

# ---------- Fase 4: Validar endpoints del servidor -----------------------
printf '\n--- Fase 3: Validaciones programaticas ---\n'

# /api/health
HEALTH_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/api/health" 2>/dev/null || echo "")
if echo "$HEALTH_BODY" | grep -q '"status".*"UP"'; then
  report_pass "GET /api/health retorna UP"
else
  report_fail "GET /api/health retorna UP" "body: $HEALTH_BODY"
fi

# / (pagina principal HTML)
INDEX_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/" 2>/dev/null || echo "")
if echo "$INDEX_BODY" | grep -q 'Padron Electoral'; then
  report_pass "GET / retorna HTML de la cabina"
else
  report_fail "GET / retorna HTML de la cabina" "no se encontro marcador 'Padron Electoral'"
fi

# /api/service/emission-context (requiere mock mixer)
EC_BODY=$(curl -sf "http://127.0.0.1:${STATION_PORT}/api/service/emission-context" 2>/dev/null || echo "")
if echo "$EC_BODY" | grep -q 'session_id'; then
  report_pass "GET /api/service/emission-context conecta con mixer"
else
  report_fail "GET /api/service/emission-context conecta con mixer" "body: $EC_BODY"
fi

# /api/ballot/preview (POST con cedula de prueba)
BALLOT_BODY="districtCode=02&presidentialParty=03&senatorsNationalParty=04&senatorsNationalPv1=01&senatorsNationalPv2=02&senatorsRegionalParty=04&senatorsRegionalPv1=01&deputiesParty=03&deputiesPv1=01&deputiesPv2=04&andeanParty=05&andeanPv1=03&andeanPv2=04"
PREVIEW_BODY=$(curl -sf -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "$BALLOT_BODY" "http://127.0.0.1:${STATION_PORT}/api/ballot/preview" 2>/dev/null || echo "")
if echo "$PREVIEW_BODY" | grep -q '"lineCount"'; then
  report_pass "POST /api/ballot/preview retorna cedula"
else
  report_fail "POST /api/ballot/preview retorna cedula" "body: $PREVIEW_BODY"
fi

# /api/ballot/submit (POST — cifrado real + envio a mock mixer)
SUBMIT_BODY=$(curl -sf -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "$BALLOT_BODY" "http://127.0.0.1:${STATION_PORT}/api/ballot/submit" 2>/dev/null || echo "")
if echo "$SUBMIT_BODY" | grep -q 'receiptAccepted.*true'; then
  report_pass "POST /api/ballot/submit cifra y recibe recibo aceptado"
else
  report_fail "POST /api/ballot/submit cifra y recibe recibo aceptado" "body truncado: $(echo "$SUBMIT_BODY" | head -c 200)"
fi

# ---------- Fase 5: Abrir Safari (opcional) ------------------------------
if [[ "$OPEN_BROWSER" == "1" && "$HEALTH_OK" -eq 1 ]]; then
  printf '\n--- Fase 4: Abriendo Safari ---\n'
  open "http://127.0.0.1:${STATION_PORT}"
  report_pass "Safari abierto en la estacion"
  printf '  La estacion esta activa. Presione Enter para cerrar...\n'
  read -r
fi

# ---------- Resumen ------------------------------------------------------
printf '\n=== Resumen autoprueba macOS ===\n'
for t in "${TESTS[@]}"; do
  printf '  %s\n' "$t"
done
printf '\nTotal: %d pasaron, %d fallaron (de %d)\n' "$PASS" "$FAIL" "$((PASS + FAIL))"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
