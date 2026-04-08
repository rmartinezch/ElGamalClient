#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# test-cifrador-con-mezcladora.sh
#
# Prueba integral: compila el cifrador, crea una sesion Verificatum de
# 3 parties por CLI, cifra votos, mezcla, descifra y compara el
# resultado con los votos originales.
#
# Uso:
#   ./scripts/ubuntu/pruebas/test-cifrador-con-mezcladora.sh [VOTOS]
#
#   VOTOS  ruta al archivo de votos (por defecto: recursos/shuffled_votes.txt)
#
# Requisitos:
#   - Java 21, GCC, autotools, libgmp-dev
#   - Verificatum VMN instalado (vmn, vmni, vmnc, vmnd, vog, vmnv)
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
VOTES_FILE="${1:-$PROJECT_ROOT/recursos/shuffled_votes.txt}"
PARTIES=3
THRESHOLD=2
SID="ONPE"
PROTOCOL_NAME="EleccionDemo"
HOST="127.0.0.1"
BASE_HINT=4040
BASE_HTTP=8040
SESSION_DIR=""
PIDS=()

# ── Limpieza al salir ────────────────────────────────────────────────
cleanup() {
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  if [[ -n "$SESSION_DIR" && -d "$SESSION_DIR" ]]; then
    echo ""
    echo "[cleanup] Los artefactos de la sesion quedan en: $SESSION_DIR"
  fi
}
trap cleanup EXIT

# ── Helpers ──────────────────────────────────────────────────────────
party_name() { printf "party%02d" "$1"; }

party_dir() { echo "$SESSION_DIR/$(party_name "$1")"; }

run_in_party() {
  local idx="$1"; shift
  (cd "$(party_dir "$idx")" && bash -lc "$*")
}

check_bin() {
  for cmd in "$@"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "[ERROR] No se encontro '$cmd'. Instale Verificatum VMN antes de continuar." >&2
      exit 1
    fi
  done
}

separator() {
  echo ""
  echo "════════════════════════════════════════════════════════════════"
  echo "  $1"
  echo "════════════════════════════════════════════════════════════════"
}

# ── Validaciones previas ─────────────────────────────────────────────
separator "Validando requisitos"

check_bin java javac vmn vmni vmnc vog

if [[ ! -f "$VOTES_FILE" ]]; then
  echo "[ERROR] No se encontro el archivo de votos: $VOTES_FILE" >&2
  exit 1
fi

TOTAL_VOTES=$(wc -l < "$VOTES_FILE")
echo "  Java:   $(java -version 2>&1 | head -1)"
echo "  VMN:    $(vmn -version 2>&1 | head -1 || echo 'ok')"
echo "  Votos:  $VOTES_FILE ($TOTAL_VOTES lineas)"

# ── Paso 0: Compilar el cifrador ────────────────────────────────────
separator "Paso 0 — Compilar el cifrador"

JAR="$PROJECT_ROOT/prebuilt/java/ElGamalCipher-1.1.0.jar"
if [[ -f "$JAR" ]]; then
  echo "  JAR ya existe: $JAR ($(stat -c%s "$JAR") bytes)"
  echo "  Saltando compilacion. Borre el JAR si quiere forzar recompilacion."
else
  "$PROJECT_ROOT/scripts/ubuntu/compilacion/build-cifrador.sh"
fi

if [[ ! -f "$JAR" ]]; then
  echo "[ERROR] No se encontro el JAR: $JAR" >&2
  exit 1
fi

# ── Paso 1: Crear layout de sesion VMN ──────────────────────────────
separator "Paso 1 — Crear sesion Verificatum ($PARTIES parties, threshold $THRESHOLD)"

SESSION_DIR="$PROJECT_ROOT/target/test-mezcladora-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$SESSION_DIR"

echo "  Sesion: $SESSION_DIR"
echo "  Generando grupo eliptico P-256..."
PGROUP=$(cd "$SESSION_DIR" && vog -gen ECqPGroup -name 'P-256')
echo "  PGroup: ${PGROUP:0:60}..."

for i in $(seq 1 $PARTIES); do
  PDIR=$(party_dir "$i")
  mkdir -p "$PDIR"

  echo "  Creando protocolo para $(party_name "$i")..."
  run_in_party "$i" vmni -prot \
    -sid "$SID" \
    -name "$PROTOCOL_NAME" \
    -nopart "$PARTIES" \
    -thres "$THRESHOLD" \
    -pgroup "'$PGROUP'"

  HINT_PORT=$((BASE_HINT + i))
  HTTP_PORT=$((BASE_HTTP + i))

  run_in_party "$i" "
    vog -rndinit RandomDevice /dev/urandom >/dev/null 2>&1 || true
    RAND=\$(vog -gen RandomDevice /dev/urandom)
    vmni -party -e \
      -name '$(party_name "$i")' \
      -hint '$HOST:$HINT_PORT' \
      -http 'http://$HOST:$HTTP_PORT' \
      -rand \"\$RAND\"
    cp localProtInfo.xml protInfo$(printf '%02d' "$i").xml
  "
done

echo "  Compartiendo protInfo entre parties..."
for i in $(seq 1 $PARTIES); do
  for j in $(seq 1 $PARTIES); do
    [[ "$i" -eq "$j" ]] && continue
    cp "$(party_dir "$i")/protInfo$(printf '%02d' "$i").xml" \
       "$(party_dir "$j")/"
  done
done

MERGE_INPUTS=""
for i in $(seq 1 $PARTIES); do
  MERGE_INPUTS="$MERGE_INPUTS protInfo$(printf '%02d' "$i").xml"
done

echo "  Fusionando protocolo global..."
for i in $(seq 1 $PARTIES); do
  run_in_party "$i" vmni -merge $MERGE_INPUTS protInfo.xml
done

echo "  Layout de sesion completo."

# ── Paso 2: Keygen distribuido ──────────────────────────────────────
separator "Paso 2 — Keygen distribuido (generacion de llave)"

echo "  Lanzando vmn -keygen en las $PARTIES parties simultaneamente..."

for i in $(seq 1 $PARTIES); do
  (
    cd "$(party_dir "$i")"
    bash -lc "
      rm -f publicKey publicKey_ext
      vmn -keygen -e publicKey
      vmnc -pkey -outi native protInfo.xml publicKey publicKey_ext
    "
  ) &
  PIDS+=($!)
  sleep 1
done

echo "  Esperando a que termine el keygen..."
for pid in "${PIDS[@]}"; do
  wait "$pid"
done
PIDS=()

PK="$(party_dir 1)/publicKey_ext"
if [[ ! -f "$PK" ]]; then
  echo "[ERROR] Keygen fallo: no se genero publicKey_ext en party01" >&2
  exit 1
fi
echo "  Llave publica generada: $PK ($(stat -c%s "$PK") bytes)"

# ── Paso 3: Cifrar votos ────────────────────────────────────────────
separator "Paso 3 — Cifrar votos con ElGamalCipher"

CIPHERTEXTS_EXT="$SESSION_DIR/ciphertexts_ext"

java -jar "$JAR" \
  "$PK" \
  "$VOTES_FILE" \
  "$CIPHERTEXTS_EXT" \
  -sw \
  -p

if [[ ! -f "$CIPHERTEXTS_EXT" ]]; then
  echo "[ERROR] El cifrador no genero salida: $CIPHERTEXTS_EXT" >&2
  exit 1
fi
echo "  Ciphertexts generados: $CIPHERTEXTS_EXT ($(stat -c%s "$CIPHERTEXTS_EXT") bytes)"

# ── Paso 4: Convertir y distribuir ciphertexts ──────────────────────
separator "Paso 4 — Convertir ciphertexts a formato Verificatum y distribuir"

cp "$CIPHERTEXTS_EXT" "$(party_dir 1)/ciphertexts_ext"
run_in_party 1 vmnc -ciphs -sloppy -ini native -width 1 \
  protInfo.xml ciphertexts_ext ciphertexts

echo "  Conversion OK en party01."
echo "  Distribuyendo a las demas parties..."
for i in $(seq 2 $PARTIES); do
  cp "$(party_dir 1)/ciphertexts"     "$(party_dir "$i")/"
  cp "$(party_dir 1)/ciphertexts_ext" "$(party_dir "$i")/"
done
echo "  Ciphertexts replicados a todas las parties."

# ── Paso 5: Shuffle (mezcla) ────────────────────────────────────────
separator "Paso 5 — Shuffle (mezcla distribuida)"

echo "  Lanzando vmn -shuffle en las $PARTIES parties..."

for i in $(seq 1 $PARTIES); do
  (
    cd "$(party_dir "$i")"
    bash -lc "rm -f ciphertextsout && vmn -shuffle privInfo.xml protInfo.xml ciphertexts ciphertextsout"
  ) &
  PIDS+=($!)
  sleep 1
done

echo "  Esperando a que termine la mezcla..."
for pid in "${PIDS[@]}"; do
  wait "$pid"
done
PIDS=()

if [[ ! -f "$(party_dir 1)/ciphertextsout" ]]; then
  echo "[ERROR] Shuffle fallo: no se genero ciphertextsout en party01" >&2
  exit 1
fi
echo "  Mezcla completada."

# ── Paso 6: Decrypt (descifrado) ────────────────────────────────────
separator "Paso 6 — Decrypt (descifrado distribuido)"

echo "  Lanzando vmn -decrypt en las $PARTIES parties..."

for i in $(seq 1 $PARTIES); do
  (
    cd "$(party_dir "$i")"
    bash -lc "
      rm -f plaintexts_orig plaintexts
      vmn -decrypt privInfo.xml protInfo.xml ciphertextsout plaintexts_orig
      vmnc -plain -outi native protInfo.xml plaintexts_orig plaintexts
    "
  ) &
  PIDS+=($!)
  sleep 1
done

echo "  Esperando a que termine el descifrado..."
for pid in "${PIDS[@]}"; do
  wait "$pid"
done
PIDS=()

PLAINTEXTS="$(party_dir 1)/plaintexts"
if [[ ! -f "$PLAINTEXTS" ]]; then
  echo "[ERROR] Descifrado fallo: no se genero plaintexts en party01" >&2
  exit 1
fi
echo "  Descifrado completado: $PLAINTEXTS ($(stat -c%s "$PLAINTEXTS") bytes)"

# ── Paso 7: Validacion ──────────────────────────────────────────────
separator "Paso 7 — Validacion de resultados"

echo "  Votos originales:    $VOTES_FILE ($TOTAL_VOTES lineas)"
echo "  Votos descifrados:   $PLAINTEXTS"

ORIG_SORTED=$(sort "$VOTES_FILE")
PLAIN_SORTED=$(sort "$PLAINTEXTS")

if [[ "$ORIG_SORTED" == "$PLAIN_SORTED" ]]; then
  echo ""
  echo "  ✔ PRUEBA EXITOSA"
  echo ""
  echo "  Los votos descifrados coinciden con los originales."
  echo "  El cifrador cifro correctamente, la mezcla preservo el contenido"
  echo "  y el descifrado distribuido recupero los votos en texto plano."
  echo ""
  RESULT=0
else
  echo ""
  echo "  ✘ PRUEBA FALLIDA"
  echo ""
  echo "  Los votos descifrados NO coinciden con los originales."
  echo "  Primeras lineas del resultado descifrado:"
  head -5 "$PLAINTEXTS"
  echo ""
  RESULT=1
fi

separator "Resumen"
echo "  Sesion:       $SESSION_DIR"
echo "  Parties:      $PARTIES (threshold $THRESHOLD)"
echo "  Votos:        $TOTAL_VOTES"
echo "  Resultado:    $([ $RESULT -eq 0 ] && echo 'OK' || echo 'FALLO')"
echo ""

exit $RESULT
