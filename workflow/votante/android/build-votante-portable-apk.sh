#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
WORKFLOW_DIR="$PROJECT_ROOT/workflow/votante/android"
APP_DIR="$WORKFLOW_DIR/app"
APP_LIBS_DIR="$APP_DIR/libs"
APP_ASSETS_DIR="$APP_DIR/src/main/assets/cifrador"
APP_JNILIBS_DIR="$APP_DIR/src/main/jniLibs"
BASE_ANDROID_DIR="$PROJECT_ROOT/android"
BASE_JNILIBS_DIR="$BASE_ANDROID_DIR/app/src/main/jniLibs"
DIST_DIR="$PROJECT_ROOT/dist/android"
OUTPUT_APK_NAME="${OUTPUT_APK_NAME:-VotanteAndroid-portable.apk}"
OUTPUT_APK_PATH="$DIST_DIR/$OUTPUT_APK_NAME"
OUTPUT_SHA_PATH="$OUTPUT_APK_PATH.sha256"
GRADLEW_PATH="$BASE_ANDROID_DIR/gradlew"
JAR_TARGET_NAME="${JAR_TARGET_NAME:-ElGamalCipher-android.jar}"
JAR_APP_PATH="$APP_LIBS_DIR/$JAR_TARGET_NAME"
JAR_ASSET_PATH="$APP_ASSETS_DIR/$JAR_TARGET_NAME"
ABI_LIST=("arm64-v8a" "x86_64")
JNI_LIBS=("libvecj-2.2.0.so" "libvmgj-1.3.0.so")

source "$PROJECT_ROOT/scripts/android/lib-android-env.sh"

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
  local abi lib source_path target_dir

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
    "$PROJECT_ROOT/scripts/android/build-jni.sh"
  fi

  for abi in "${ABI_LIST[@]}"; do
    target_dir="$APP_JNILIBS_DIR/$abi"
    mkdir -p "$target_dir"
    for lib in "${JNI_LIBS[@]}"; do
      source_path="$BASE_JNILIBS_DIR/$abi/$lib"
      require_file "$source_path"
      cp -f "$source_path" "$target_dir/$lib"
    done
  done
}

main() {
  local sdk_root app_version jar_source apk_source
  app_version="$(project_version)"
  jar_source="$PROJECT_ROOT/target/ElGamalCipher-$app_version.jar"
  apk_source="$APP_DIR/build/outputs/apk/debug/app-debug.apk"
  sdk_root="$(resolve_android_sdk_root "$PROJECT_ROOT")"

  mkdir -p "$APP_LIBS_DIR" "$APP_ASSETS_DIR" "$DIST_DIR"
  printf 'sdk.dir=%s\n' "$sdk_root" > "$WORKFLOW_DIR/local.properties"

  echo "[votante-android] Recompilando jar portable para Android (Java 17)..."
  (
    cd "$PROJECT_ROOT"
    mvn -q -DskipTests -Dmaven.compiler.release=17 package
  )
  require_file "$jar_source"

  cp -f "$jar_source" "$JAR_APP_PATH"
  cp -f "$jar_source" "$JAR_ASSET_PATH"

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
  unzip -l "$OUTPUT_APK_PATH" | rg 'lib/(arm64-v8a|x86_64)/(libvecj-2\.2\.0\.so|libvmgj-1\.3\.0\.so)|assets/cifrador/ElGamalCipher-android\.jar'

  printf '\n[votante-android] APK generado en: %s\n' "$OUTPUT_APK_PATH"
  printf '[votante-android] SHA-256: %s\n' "$OUTPUT_SHA_PATH"
}

main "$@"
