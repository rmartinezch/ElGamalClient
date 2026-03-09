#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
"$PROJECT_ROOT/scripts/android/build-debug.sh"
cd "$PROJECT_ROOT/android"

./gradlew :app:installDebug
