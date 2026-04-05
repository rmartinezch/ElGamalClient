#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
VCR_SRC_DIR="$ROOT_DIR/native/verificatum-src/verificatum-vcr-3.1.0"
VCR_JAR="$VCR_SRC_DIR/verificatum-vcr-3.1.0.jar"
VCR_REPO_DIR="$ROOT_DIR/native/verificatum-jars"
ELGAMAL_JAR="$ROOT_DIR/target/ElGamalCipher-1.1.0.jar"
PREBUILT_JAR="$ROOT_DIR/prebuilt/java/ElGamalCipher-1.1.0.jar"
IOS_POM="$ROOT_DIR/platform/ios/pom.xml"
IOS_TARGET_DIR="$ROOT_DIR/platform/ios/target/gluonfx/arm64-ios"
IOS_GVM_APP_DIR="$IOS_TARGET_DIR/gvm/Votante iOS"
IOS_APP_DIR="$IOS_TARGET_DIR/Votante iOS.app"
APP_NAME="Votante iOS"
APP_BUNDLE_ID="pe.gob.onpe.votodigital.votante.ios"
APP_BINARY="$IOS_APP_DIR/$APP_NAME"
MANUAL_BINARY="/tmp/VotanteIOS-manual"

JAVA21_HOME_DEFAULT="$ROOT_DIR/.tools/jdk-21"
GRAALVM_HOME_DEFAULT="$ROOT_DIR/.tools/graalvm-svm-java17-darwin-m1-gluon-22.1.0.1-Final/Contents/Home"
JAVA21_HOME="${JAVA21_HOME:-$JAVA21_HOME_DEFAULT}"
GRAALVM_HOME="${GRAALVM_HOME:-$GRAALVM_HOME_DEFAULT}"
JAVA_BIN_DIR="$JAVA21_HOME/bin"
MAVEN_REPO_GLOBAL="${MAVEN_REPO_GLOBAL:-$HOME/.m2/repository}"
MAVEN_REPO_PROJECT="$ROOT_DIR/.mvn/local-repo"
IOS_SDK_PATH="${IOS_SDK_PATH:-$(xcrun --sdk iphonesimulator --show-sdk-path)}"
JAVAFX_IOS_LIB_DIR="${JAVAFX_IOS_LIB_DIR:-$HOME/.gluon/substrate/javafxStaticSdk/21-ea+11.3/ios-arm64/sdk/lib}"
STATIC_JDK_IOS_LIB_DIR="${STATIC_JDK_IOS_LIB_DIR:-$HOME/.gluon/substrate/javaStaticSdk/18-ea+prep18-9/ios-arm64/staticjdk/lib/static}"
BOOTED_DEVICE="${BOOTED_DEVICE:-booted}"
INSTALL_TO_SIMULATOR="${INSTALL_TO_SIMULATOR:-1}"
LAUNCH_APP="${LAUNCH_APP:-1}"

log() {
  printf '[ios-autonomo] %s\n' "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Falta el comando requerido: %s\n' "$1" >&2
    exit 1
  }
}

ensure_prereqs() {
  require_cmd clang
  require_cmd codesign
  require_cmd nm
  require_cmd otool
  require_cmd python3
  require_cmd xcrun
  require_cmd shasum
  [[ -x "$ROOT_DIR/mvnw" ]] || {
    printf 'No se encontro mvnw en %s\n' "$ROOT_DIR" >&2
    exit 1
  }
  [[ -x "$JAVA_BIN_DIR/java" ]] || {
    printf 'No se encontro Java 21 en %s\n' "$JAVA_BIN_DIR" >&2
    exit 1
  }
  [[ -x "$GRAALVM_HOME/bin/native-image" ]] || {
    printf 'No se encontro GraalVM en %s\n' "$GRAALVM_HOME" >&2
    exit 1
  }
}

configure_vcr_if_needed() {
  if [[ ! -f "$VCR_SRC_DIR/Makefile" ]]; then
    log "Configurando verificatum-vcr"
    (
      cd "$VCR_SRC_DIR"
      PATH="$JAVA_BIN_DIR:$PATH" JAVA_HOME="$JAVA21_HOME" sh ./configure
    )
  fi
}

build_pure_java_vcr() {
  log "Compilando verificatum-vcr en Java puro"
  configure_vcr_if_needed
  (
    cd "$VCR_SRC_DIR"
    PATH="$JAVA_BIN_DIR:$PATH" JAVA_HOME="$JAVA21_HOME" make clean >/dev/null 2>&1 || true
    PATH="$JAVA_BIN_DIR:$PATH" JAVA_HOME="$JAVA21_HOME" make -j4 JAVACFLAGS='--release 17'
  )
  [[ -f "$VCR_JAR" ]] || {
    printf 'No se genero %s\n' "$VCR_JAR" >&2
    exit 1
  }
}

install_vcr_to_local_catalog() {
  log "Instalando verificatum-vcr en $VCR_REPO_DIR"
  PATH="$JAVA_BIN_DIR:$PATH" "$ROOT_DIR/mvnw" -q \
    install:install-file \
    -DgroupId=com.verificatum \
    -DartifactId=verificatum-vcr \
    -Dversion=3.1.0 \
    -Dpackaging=jar \
    -Dfile="$VCR_JAR" \
    -DlocalRepositoryPath="$VCR_REPO_DIR" \
    -DgeneratePom=true
}

install_vcr_to_repo() {
  local repo_path="$1"
  log "Instalando verificatum-vcr en $repo_path"
  PATH="$JAVA_BIN_DIR:$PATH" "$ROOT_DIR/mvnw" -q \
    -Dmaven.repo.local="$repo_path" \
    install:install-file \
    -DgroupId=com.verificatum \
    -DartifactId=verificatum-vcr \
    -Dversion=3.1.0 \
    -Dpackaging=jar \
    -Dfile="$VCR_JAR" \
    -DgeneratePom=true
}

build_elgamal_fat_jar() {
  log "Compilando ElGamalCipher en release 17"
  PATH="$JAVA_BIN_DIR:$PATH" JAVA_HOME="$JAVA21_HOME" "$ROOT_DIR/mvnw" -q clean -DskipTests package
  [[ -f "$ELGAMAL_JAR" ]] || {
    printf 'No se genero %s\n' "$ELGAMAL_JAR" >&2
    exit 1
  }
}

install_elgamal_to_repo() {
  local repo_path="$1"
  log "Instalando ElGamalCipher en $repo_path"
  PATH="$JAVA_BIN_DIR:$PATH" "$ROOT_DIR/mvnw" -q \
    -Dmaven.repo.local="$repo_path" \
    install:install-file \
    -DgroupId=pe.gob.onpe.votodigital \
    -DartifactId=ElGamalCipher \
    -Dversion=1.1.0 \
    -Dpackaging=jar \
    -Dfile="$ELGAMAL_JAR" \
    -DgeneratePom=true
}

sync_prebuilt_jar() {
  log "Actualizando prebuilt/java/ElGamalCipher-1.1.0.jar"
  cp "$ELGAMAL_JAR" "$PREBUILT_JAR"
}

cleanup_vcr_worktree() {
  log "Limpiando artefactos temporales de verificatum-vcr"
  chmod 644 \
    "$VCR_SRC_DIR/bin/vbt" \
    "$VCR_SRC_DIR/bin/vchild" \
    "$VCR_SRC_DIR/bin/vcpuosv" \
    "$VCR_SRC_DIR/bin/vcr-3.1.0-info" \
    "$VCR_SRC_DIR/bin/vog" \
    "$VCR_SRC_DIR/bin/vrunning" \
    "$VCR_SRC_DIR/bin/vspt" \
    "$VCR_SRC_DIR/bin/vtest" || true
  cat > "$VCR_SRC_DIR/bin/vcr-3.1.0-info" <<'EOF'
#!/bin/sh

if test x$1 = x"bin";
then
    printf "/usr/local/bin"
elif test x$1 = x"lib";
then
    printf "::/usr/local/lib"
elif test x$1 = x"jar";
then
    printf "/usr/local/share/java/verificatum-vcr-3.1.0.jar::"
elif test x$1 = x"version";
then
    printf "3.1.0"
elif test x$1 = x"complete";
then
    printf "vcr-3.1.0"
else
    printf "Illegal parameter! (%s)\n" $1
fi
EOF
  rm -rf \
    "$VCR_SRC_DIR/Makefile" \
    "$VCR_SRC_DIR/classes.stamp" \
    "$VCR_SRC_DIR/classes" \
    "$VCR_SRC_DIR/config.status" \
    "$VCR_SRC_DIR/preprocessor.m4" \
    "$VCR_SRC_DIR/scriptmacros.m4" \
    "$VCR_SRC_DIR/src/java/com/verificatum/arithm/ECqPGroup.java" \
    "$VCR_SRC_DIR/src/java/com/verificatum/arithm/ECqPGroupElement.java" \
    "$VCR_SRC_DIR/src/java/com/verificatum/arithm/LargeInteger.java" \
    "$VCR_SRC_DIR/src/java/com/verificatum/arithm/LargeIntegerFixModPowTab.java" \
    "$VCR_SRC_DIR/verificatum-vcr-3.1.0.jar"
  rm -rf "$VCR_REPO_DIR/org"
}

run_gluon_build_for_ios_sim() {
  log "Ejecutando build de Gluon para ios-sim"
  PATH="$JAVA_BIN_DIR:$PATH" JAVA_HOME="$JAVA21_HOME" "$ROOT_DIR/mvnw" \
    -f "$IOS_POM" \
    -Dmaven.repo.local="$MAVEN_REPO_GLOBAL" \
    clean -DskipTests gluonfx:build || true
}

locate_native_image_object() {
  find "$IOS_TARGET_DIR/gvm/tmp" -maxdepth 2 -type f \
    -name 'pe.gob.onpe.votodigital.votante.ios.iosvotanteapp.o' | sort | tail -n 1
}

recompile_simulator_shell_objects() {
  log "Recompilando shell Objective-C para iOS Simulator"
  (
    cd "$IOS_GVM_APP_DIR"
    rm -f dummy.o AppDelegate.o JvmFuncsFallbacks.o
    clang -c \
      -DSUBSTRATE \
      -x objective-c \
      -arch arm64 \
      -target arm64-apple-ios11.0-simulator \
      -mios-simulator-version-min=11.0 \
      -I"$GRAALVM_HOME/include" \
      -I"$GRAALVM_HOME/include/darwin" \
      -isysroot "$IOS_SDK_PATH" \
      -DGVM_IOS_SIM \
      -DGVM_17 \
      -I"$IOS_GVM_APP_DIR" \
      dummy.c AppDelegate.m JvmFuncsFallbacks.c
  )
}

manual_link_simulator_binary() {
  local svm_object="$1"
  log "Enlazando ejecutable manual para simulador"
  clang \
    "$IOS_GVM_APP_DIR/dummy.o" \
    "$IOS_GVM_APP_DIR/AppDelegate.o" \
    "$IOS_GVM_APP_DIR/JvmFuncsFallbacks.o" \
    "$svm_object" \
    -ljava -lnio -lzip -lnet -lprefs -ljvm -lfdlibm -lz -ldl -lj2pkcs11 -ljaas -lextnet -lstdc++ \
    -w -fPIC \
    -arch arm64 \
    -target arm64-apple-ios11.0-simulator \
    -mios-simulator-version-min=11.0 \
    -isysroot "$IOS_SDK_PATH" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libprism_es2.a" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libglass.a" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libjavafx_font.a" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libprism_common.a" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libjavafx_iio.a" \
    -Wl,-force_load,"$JAVAFX_IOS_LIB_DIR/libwebview.a" \
    -lpthread -llibchelper -lffi -ldarwin \
    -Wl,-framework,Foundation \
    -Wl,-framework,UIKit \
    -Wl,-framework,CoreGraphics \
    -Wl,-framework,MobileCoreServices \
    -Wl,-framework,OpenGLES \
    -Wl,-framework,CoreText \
    -Wl,-framework,QuartzCore \
    -Wl,-framework,ImageIO \
    -Wl,-framework,CoreBluetooth \
    -Wl,-framework,CoreImage \
    -Wl,-framework,CoreLocation \
    -Wl,-framework,CoreMedia \
    -Wl,-framework,CoreMotion \
    -Wl,-framework,CoreVideo \
    -Wl,-framework,Accelerate \
    -Wl,-framework,AVFoundation \
    -Wl,-framework,AudioToolbox \
    -Wl,-framework,MediaPlayer \
    -Wl,-framework,UserNotifications \
    -Wl,-framework,ARKit \
    -Wl,-framework,AVKit \
    -Wl,-framework,SceneKit \
    -Wl,-framework,StoreKit \
    -Wl,-framework,ModelIO \
    -Wl,-framework,WebKit \
    -o "$MANUAL_BINARY" \
    -L"$JAVAFX_IOS_LIB_DIR" \
    -L"$GRAALVM_HOME/lib/svm/clibraries/27/ios-arm64" \
    -L"$STATIC_JDK_IOS_LIB_DIR"
}

write_info_plist() {
  cat > "$IOS_APP_DIR/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDisplayName</key>
  <string>Votante iOS</string>
  <key>CFBundleExecutable</key>
  <string>Votante iOS</string>
  <key>CFBundleIdentifier</key>
  <string>pe.gob.onpe.votodigital.votante.ios</string>
  <key>CFBundleName</key>
  <string>Votante iOS</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>1.0</string>
  <key>CFBundleSupportedPlatforms</key>
  <array>
    <string>iPhoneSimulator</string>
  </array>
  <key>CFBundleVersion</key>
  <string>1.0</string>
  <key>DTPlatformName</key>
  <string>iphonesimulator</string>
  <key>MinimumOSVersion</key>
  <string>17.0</string>
  <key>NSAppTransportSecurity</key>
  <dict>
    <key>NSAllowsArbitraryLoads</key>
    <true/>
  </dict>
  <key>UIDeviceFamily</key>
  <array>
    <integer>1</integer>
    <integer>2</integer>
  </array>
  <key>UILaunchStoryboardName</key>
  <string></string>
  <key>UIRequiredDeviceCapabilities</key>
  <array>
    <string>arm64</string>
  </array>
</dict>
</plist>
EOF
}

patch_cpu_features_for_simulator() {
  log "Aplicando parche de CPU features al ejecutable final"
  python3 - <<'PY' "$APP_BINARY"
from pathlib import Path
import re
import subprocess
import sys

binary = Path(sys.argv[1])
nm_output = subprocess.check_output(["nm", "-nm", str(binary)], text=True)
match = re.search(r"^([0-9a-fA-F]+)\s+\(__TEXT,__text\)\s+external _determineCPUFeatures$", nm_output, re.M)
if not match:
    raise SystemExit("No se encontro _determineCPUFeatures en el binario")
symbol_addr = int(match.group(1), 16)

otool_output = subprocess.check_output(["otool", "-l", str(binary)], text=True)
segment_match = re.search(
    r"segname __TEXT.*?vmaddr (0x[0-9a-fA-F]+).*?fileoff (\d+)",
    otool_output,
    re.S,
)
if not segment_match:
    raise SystemExit("No se pudo localizar el segmento __TEXT")
text_vmaddr = int(segment_match.group(1), 16)
text_fileoff = int(segment_match.group(2))

patch_offset = (symbol_addr - text_vmaddr) + text_fileoff + 0x10
patch = bytes.fromhex("e90740f9280080522801003928050039280d003928150039")
data = bytearray(binary.read_bytes())
data[patch_offset:patch_offset + len(patch)] = patch
binary.write_bytes(data)
PY
}

prepare_app_bundle() {
  log "Preparando bundle .app"
  mkdir -p "$IOS_APP_DIR"
  cp "$MANUAL_BINARY" "$APP_BINARY"
  chmod +x "$APP_BINARY"
  write_info_plist
  codesign --force --sign - "$IOS_APP_DIR"
  patch_cpu_features_for_simulator
  codesign --force --sign - "$IOS_APP_DIR"
}

install_and_launch_app() {
  if [[ "$INSTALL_TO_SIMULATOR" != "1" ]]; then
    log "Se omite instalacion en simulador (INSTALL_TO_SIMULATOR=$INSTALL_TO_SIMULATOR)"
    return
  fi

  log "Instalando app en el simulador booted"
  xcrun simctl install "$BOOTED_DEVICE" "$IOS_APP_DIR"

  if [[ "$LAUNCH_APP" == "1" ]]; then
    log "Lanzando app en el simulador"
    xcrun simctl launch --terminate-running-process "$BOOTED_DEVICE" "$APP_BUNDLE_ID"
  fi
}

print_summary() {
  log "Build terminado"
  printf '  VCR puro: %s\n' "$VCR_JAR"
  printf '  Fat jar: %s\n' "$ELGAMAL_JAR"
  printf '  Prebuilt: %s\n' "$PREBUILT_JAR"
  printf '  App bundle: %s\n' "$IOS_APP_DIR"
}

main() {
  ensure_prereqs
  build_pure_java_vcr
  install_vcr_to_local_catalog
  install_vcr_to_repo "$MAVEN_REPO_GLOBAL"
  build_elgamal_fat_jar
  sync_prebuilt_jar
  install_elgamal_to_repo "$MAVEN_REPO_PROJECT"
  install_elgamal_to_repo "$MAVEN_REPO_GLOBAL"
  cleanup_vcr_worktree
  run_gluon_build_for_ios_sim

  local svm_object
  svm_object="$(locate_native_image_object)"
  [[ -n "$svm_object" && -f "$svm_object" ]] || {
    printf 'No se encontro el objeto principal de native-image.\n' >&2
    exit 1
  }

  recompile_simulator_shell_objects
  manual_link_simulator_binary "$svm_object"
  prepare_app_bundle
  install_and_launch_app
  print_summary
}

main "$@"
