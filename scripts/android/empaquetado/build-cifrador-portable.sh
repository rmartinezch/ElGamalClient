#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
APP_NAME="${APP_NAME:-Cifrador}"
APP_VERSION="${APP_VERSION:-1.1.0}"
SKIP_ANDROID_JNI="${SKIP_ANDROID_JNI:-1}"
source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"
ANDROID_DIR="$(resolve_android_project_dir "$PROJECT_ROOT")"
IMAGE_ROOT="$PROJECT_ROOT/dist/android/image/$APP_NAME"
AAR_DIR="$IMAGE_ROOT/aar"
JNILIBS_DIR="$IMAGE_ROOT/jniLibs"
METADATA_DIR="$IMAGE_ROOT/metadata"
AAR_SOURCE="$PROJECT_ROOT/prebuilt/android/aar/ElGamalCipher-android-debug.aar"
PREBUILT_JNI_ROOT="$PROJECT_ROOT/prebuilt/android/jniLibs"
AAR_NAME="$APP_NAME-android-debug.aar"

cd "$PROJECT_ROOT"

SKIP_ANDROID_JNI="$SKIP_ANDROID_JNI" "$PROJECT_ROOT/scripts/android/compilacion/build-cifrador.sh"

if [[ ! -f "$AAR_SOURCE" ]]; then
  echo "[android-portable] ERROR: falta AAR principal: $AAR_SOURCE" >&2
  exit 1
fi

if [[ ! -d "$PREBUILT_JNI_ROOT" ]]; then
  echo "[android-portable] ERROR: faltan jniLibs Android en: $PREBUILT_JNI_ROOT" >&2
  exit 1
fi

rm -rf "$IMAGE_ROOT"
mkdir -p "$AAR_DIR" "$JNILIBS_DIR" "$METADATA_DIR"

cp "$AAR_SOURCE" "$AAR_DIR/$AAR_NAME"
cp -a "$PREBUILT_JNI_ROOT/." "$JNILIBS_DIR/"

cat > "$IMAGE_ROOT/README.txt" <<README
$APP_NAME Android portable

Contenido:
- aar/$AAR_NAME
- jniLibs/<abi>/libvecj-2.2.0.so
- jniLibs/<abi>/libvmgj-1.3.0.so
- metadata/checksums.sha256

Uso:
- consumir el AAR desde otra app Android
- acompañar el consumo con las bibliotecas de jniLibs empacadas aqui
README

(
  cd "$IMAGE_ROOT"
  sha256sum "aar/$AAR_NAME" > "$METADATA_DIR/checksums.sha256"
  find "jniLibs" -type f -name '*.so' -print0 | sort -z | xargs -0 sha256sum >> "$METADATA_DIR/checksums.sha256"
)

printf '\n[android-portable] Imagen generada en: %s\n' "$IMAGE_ROOT"
printf '[android-portable] AAR principal: %s\n' "$AAR_DIR/$AAR_NAME"
printf '[android-portable] JNI incluidos:\n'
find "$JNILIBS_DIR" -type f -name '*.so' -printf '  - %P\n' | sort
