#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

echo "[ubuntu] Repositorio: $ROOT_DIR"
echo "[ubuntu] Validando artefactos Maven locales de Verificatum..."

required=(
  ".mvn/local-repo/com/verificatum/verificatum-vmgj/1.3.0/verificatum-vmgj-1.3.0.jar"
  ".mvn/local-repo/com/verificatum/verificatum-vecj/2.2.0/verificatum-vecj-2.2.0.jar"
  ".mvn/local-repo/com/verificatum/verificatum-vcr-vmgj-vecj/3.1.0/verificatum-vcr-vmgj-vecj-3.1.0.jar"
)

missing=0
for artifact in "${required[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    echo "[ubuntu] FALTA: $artifact"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  echo "[ubuntu] No se encontraron todos los artefactos en .mvn/local-repo."
  echo "[ubuntu] Use el bootstrap Windows o instale manualmente esos jars en el repo local del proyecto."
  exit 1
fi

echo "[ubuntu] Artefactos locales presentes."
