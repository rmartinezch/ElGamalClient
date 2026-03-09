#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 4 || "$#" -gt 5 ]]; then
  echo "Uso: $0 <publicKey> <votos> <salida> <-sw|-hw> [-p]" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

scripts/ubuntu/build-jar.sh

java -jar target/ElGamalCipher-1.1.0.jar "$@"
