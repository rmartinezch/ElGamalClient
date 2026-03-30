#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT_DIR"

echo "[ubuntu] Repositorio: $ROOT_DIR"
echo "[ubuntu] Validando artefactos Maven locales de Verificatum..."

required=(
  "native/verificatum-jars/com/verificatum/verificatum-vmgj/1.3.0/verificatum-vmgj-1.3.0.jar"
  "native/verificatum-jars/com/verificatum/verificatum-vecj/2.2.0/verificatum-vecj-2.2.0.jar"
  "native/verificatum-jars/com/verificatum/verificatum-vcr-vmgj-vecj/3.1.0/verificatum-vcr-vmgj-vecj-3.1.0.jar"
)

missing=0
for artifact in "${required[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    echo "[ubuntu] FALTA: $artifact"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  echo "[ubuntu] No se encontraron todos los artefactos en native/verificatum-jars."
  echo "[ubuntu] Verifique que el directorio native/verificatum-jars contenga los JARs de Verificatum."
  exit 1
fi

echo "[ubuntu] Artefactos locales presentes en native/verificatum-jars."
