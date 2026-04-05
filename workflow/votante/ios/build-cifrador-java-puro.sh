#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
VCR_SRC_DIR="$ROOT_DIR/native/verificatum-src/verificatum-vcr-3.1.0"
VCR_JAR="$VCR_SRC_DIR/verificatum-vcr-3.1.0.jar"
VCR_REPO_DIR="$ROOT_DIR/native/verificatum-jars"
ELGAMAL_JAR="$ROOT_DIR/target/ElGamalCipher-1.1.0.jar"
PREBUILT_JAR="$ROOT_DIR/prebuilt/java/ElGamalCipher-1.1.0.jar"

JAVA21_HOME_DEFAULT="$ROOT_DIR/.tools/jdk-21"
JAVA21_HOME="${JAVA21_HOME:-$JAVA21_HOME_DEFAULT}"
JAVA_BIN_DIR="$JAVA21_HOME/bin"
MAVEN_REPO_GLOBAL="${MAVEN_REPO_GLOBAL:-$HOME/.m2/repository}"
MAVEN_REPO_PROJECT="$ROOT_DIR/.mvn/local-repo"

log() {
  printf '[cifrador-puro] %s\n' "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Falta el comando requerido: %s\n' "$1" >&2
    exit 1
  }
}

ensure_prereqs() {
  require_cmd make
  require_cmd python3
  [[ -x "$ROOT_DIR/mvnw" ]] || {
    printf 'No se encontro mvnw en %s\n' "$ROOT_DIR" >&2
    exit 1
  }
  [[ -x "$JAVA_BIN_DIR/java" ]] || {
    printf 'No se encontro Java 21 en %s\n' "$JAVA_BIN_DIR" >&2
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

print_summary() {
  log "Build del cifrador terminado"
  printf '  VCR puro: %s\n' "$VCR_REPO_DIR/com/verificatum/verificatum-vcr/3.1.0/verificatum-vcr-3.1.0.jar"
  printf '  Fat jar: %s\n' "$ELGAMAL_JAR"
  printf '  Prebuilt: %s\n' "$PREBUILT_JAR"
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
  print_summary
}

main "$@"
