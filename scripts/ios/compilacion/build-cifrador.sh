#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APP_VERSION="${APP_VERSION:-1.1.0}"
DIST_ROOT="$PROJECT_ROOT/dist/ios/library/Cifrador"

resolve_macos_classifier() {
	local arch
	arch="$(uname -m)"
	case "$arch" in
		x86_64|amd64) echo "macos-x64" ;;
		arm64|aarch64) echo "macos-arm64" ;;
		*) echo "macos-${arch}" ;;
	esac
}

"$PROJECT_ROOT/scripts/macos/empaquetado/build-cifrador-portable.sh"

NATIVE_CLASSIFIER="$(resolve_macos_classifier)"
MACOS_IMAGE_ROOT="$PROJECT_ROOT/dist/macos/image/Cifrador"
MACOS_JAR="$MACOS_IMAGE_ROOT/app/ElGamalCipher-${APP_VERSION}.jar"
MACOS_LIB_DIR="$MACOS_IMAGE_ROOT/libs/$NATIVE_CLASSIFIER"

if [[ ! -f "$MACOS_JAR" ]]; then
	echo "[ios-build] ERROR: no se encontro el JAR macOS requerido: $MACOS_JAR" >&2
	exit 1
fi

if [[ ! -f "$MACOS_LIB_DIR/libvecj-2.2.0.dylib" || ! -f "$MACOS_LIB_DIR/libvmgj-1.3.0.dylib" ]]; then
	echo "[ios-build] ERROR: faltan bibliotecas JNI macOS para bridge iOS en: $MACOS_LIB_DIR" >&2
	exit 1
fi

rm -rf "$DIST_ROOT"
mkdir -p "$DIST_ROOT/app" "$DIST_ROOT/libs/$NATIVE_CLASSIFIER"

cp -f "$MACOS_JAR" "$DIST_ROOT/app/"
# Copiar JNI libs y sus dependencias transitivas (libvec, libgmpmee, libgmp)
for dylib in "$MACOS_LIB_DIR"/*.dylib; do
  cp -f "$dylib" "$DIST_ROOT/libs/$NATIVE_CLASSIFIER/"
done

cat > "$DIST_ROOT/README.txt" <<README
Cifrador iOS bridge (host macOS)
================================

Este bundle contiene el artefacto cifrador usado por la estacion iOS en modo bridge local:

- app/ElGamalCipher-${APP_VERSION}.jar
- libs/${NATIVE_CLASSIFIER}/libvecj-2.2.0.dylib
- libs/${NATIVE_CLASSIFIER}/libvmgj-1.3.0.dylib

Uso previsto:

- la UI iOS (Safari iPhone o simulador) consume la estacion iOS en workflow/votante/ios
- la estacion ejecuta este bundle localmente en macOS para cifrar y remitir ciphertexts

Nota: la ejecucion del cifrador ocurre en el host macOS; iOS se usa como superficie de estacion.
README

printf '[ios-build] Bundle bridge generado en: %s\n' "$DIST_ROOT"
