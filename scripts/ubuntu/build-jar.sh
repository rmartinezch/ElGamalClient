#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

scripts/ubuntu/bootstrap-verificatum.sh

echo "[ubuntu] Compilando JAR..."
mvn clean package -DskipTests
