#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
WORKFLOW_DIR="$PROJECT_ROOT/workflow/votante/android"
APP_DIR="$WORKFLOW_DIR/app"
APP_GENERATED_ASSETS_DIR="$APP_DIR/src/generated/assets/votante"
BASE_ANDROID_DIR="$PROJECT_ROOT/platform/android"
PREBUILT_ANDROID_DIR="$PROJECT_ROOT/prebuilt/android/jniLibs"
BASE_JNILIBS_DIR="$PREBUILT_ANDROID_DIR"
DIST_DIR="$PROJECT_ROOT/dist/android"
OUTPUT_APK_NAME="${OUTPUT_APK_NAME:-VotanteAndroid-portable.apk}"
OUTPUT_APK_PATH="$DIST_DIR/$OUTPUT_APK_NAME"
OUTPUT_SHA_PATH="$OUTPUT_APK_PATH.sha256"
GRADLEW_PATH="$BASE_ANDROID_DIR/gradlew"
RUNTIME_CONFIG_PATH="$APP_GENERATED_ASSETS_DIR/runtime-config.json"
ABI_LIST=("arm64-v8a" "x86_64")
JNI_LIBS=("libvecj-2.2.0.so" "libvmgj-1.3.0.so")
SERVICE_BASE_URL="${VOTANTE_ANDROID_SERVICE_BASE_URL:-}"

source "$PROJECT_ROOT/scripts/android/entorno/lib-android-env.sh"

find_zip_entries() {
  if command -v rg >/dev/null 2>&1; then
    rg 'lib/(arm64-v8a|x86_64)/(libvecj-2\.2\.0\.so|libvmgj-1\.3\.0\.so)|classes(\d+)?\.dex'
    return 0
  fi

  grep -E 'lib/(arm64-v8a|x86_64)/(libvecj-2\.2\.0\.so|libvmgj-1\.3\.0\.so)|classes([0-9]+)?\.dex'
}

project_version() {
  sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$PROJECT_ROOT/pom.xml" | head -n 1
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "[votante-android] ERROR: falta archivo requerido: $path" >&2
    exit 1
  fi
}

sync_jni_libs() {
  local needs_build=0
  local abi lib source_path

  for abi in "${ABI_LIST[@]}"; do
    for lib in "${JNI_LIBS[@]}"; do
      source_path="$BASE_JNILIBS_DIR/$abi/$lib"
      if [[ ! -f "$source_path" ]]; then
        needs_build=1
      fi
    done
  done

  if [[ "$needs_build" == "1" ]]; then
    echo "[votante-android] JNI Android no encontrado en $BASE_JNILIBS_DIR. Compilando..."
    "$PROJECT_ROOT/scripts/android/compilacion/build-jni.sh"
  fi

  for abi in "${ABI_LIST[@]}"; do
    for lib in "${JNI_LIBS[@]}"; do
      source_path="$BASE_JNILIBS_DIR/$abi/$lib"
      require_file "$source_path"
    done
  done
}

main() {
  local sdk_root apk_source
  apk_source="$APP_DIR/build/outputs/apk/debug/app-debug.apk"
  sdk_root="$(resolve_android_sdk_root "$PROJECT_ROOT")"

  mkdir -p "$APP_GENERATED_ASSETS_DIR" "$DIST_DIR"
  printf 'sdk.dir=%s\n' "$sdk_root" > "$WORKFLOW_DIR/local.properties"
  printf 'sdk.dir=%s\n' "$sdk_root" > "$BASE_ANDROID_DIR/local.properties"

  cat > "$RUNTIME_CONFIG_PATH" <<CONFIG
{
  "serviceBaseUrl": "${SERVICE_BASE_URL}"
}
CONFIG

  echo "[votante-android] Sincronizando jniLibs Android..."
  sync_jni_libs

  echo "[votante-android] Compilando APK aislado de la estacion del votante..."
  (
    cd "$PROJECT_ROOT"
    "$GRADLEW_PATH" -p "$WORKFLOW_DIR" --no-daemon :app:assembleDebug
  )
  require_file "$apk_source"

  cp -f "$apk_source" "$OUTPUT_APK_PATH"
  (
    cd "$DIST_DIR"
    sha256sum "$OUTPUT_APK_NAME" > "$(basename "$OUTPUT_SHA_PATH")"
  )

  echo "[votante-android] Verificando contenido portable dentro del APK..."
  unzip -l "$OUTPUT_APK_PATH" | find_zip_entries

  printf '\n[votante-android] APK generado en: %s\n' "$OUTPUT_APK_PATH"
  printf '[votante-android] SHA-256: %s\n' "$OUTPUT_SHA_PATH"
  printf '[votante-android] serviceBaseUrl: %s\n' "${SERVICE_BASE_URL:-<descubrimiento-local>}"
}

main "$@"
