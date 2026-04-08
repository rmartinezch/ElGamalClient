#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# setup-mezcladora-vm.sh
#
# Crea y aprovisiona una VM multipass Ubuntu 24.04 con:
#   - Verificatum VMN 3.1.0
#   - Python 3
#   - Mezcladora GUI (server.py)
#
# La VM queda lista para ejecutar start_gui.sh.
#
# Uso:
#   ./scripts/android/entorno/setup-mezcladora-vm.sh [NOMBRE_VM]
#
#   NOMBRE_VM  nombre de la VM multipass (por defecto: mezcladora)
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

VM_NAME="${1:-mezcladora}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

log() { printf '[mezcladora-vm] %s\n' "$1"; }
fail() { printf '[mezcladora-vm] ERROR: %s\n' "$1" >&2; exit 1; }

command -v multipass >/dev/null 2>&1 || fail "multipass no esta instalado"

# ── Verificar si la VM ya existe ─────────────────────────────────────
if multipass info "$VM_NAME" >/dev/null 2>&1; then
  log "La VM '$VM_NAME' ya existe."
  VM_STATE="$(multipass info "$VM_NAME" --format csv | tail -1 | cut -d, -f2)"
  if [[ "$VM_STATE" != "Running" ]]; then
    log "Iniciando VM '$VM_NAME'..."
    multipass start "$VM_NAME"
  fi
else
  log "Creando VM '$VM_NAME' (Ubuntu 24.04, 4 CPU, 4G RAM, 20G disco)..."
  multipass launch 24.04 \
    --name "$VM_NAME" \
    --cpus 4 \
    --memory 4G \
    --disk 20G
fi

# ── Montar repositorio ──────────────────────────────────────────────
VM_MOUNT="/home/ubuntu/cifradorM"
if multipass info "$VM_NAME" --format csv | grep -q "$PROJECT_ROOT"; then
  log "Mount ya activo: $PROJECT_ROOT -> $VM_MOUNT"
else
  log "Montando repositorio en la VM..."
  multipass mount "$PROJECT_ROOT" "$VM_NAME":"$VM_MOUNT" || {
    log "AVISO: mount fallo. Se usara git clone en su lugar."
    multipass exec "$VM_NAME" -- bash -c "
      if [[ ! -d ~/cifradorM ]]; then
        git clone -b cifradorM https://github.com/rmartinezch/ElGamalClient.git ~/cifradorM
      else
        cd ~/cifradorM && git pull
      fi
    "
  }
fi

# ── Instalar dependencias base ──────────────────────────────────────
log "Instalando dependencias base..."
multipass exec "$VM_NAME" -- bash -c "
  sudo apt-get update -qq
  sudo apt-get install -y -qq \
    python3 python3-venv python3-pip \
    m4 cpp gcc make libtool automake autoconf libgmp-dev \
    openjdk-21-jdk wget psmisc curl
"

# ── Instalar Verificatum VMN ────────────────────────────────────────
if multipass exec "$VM_NAME" -- bash -lc "command -v vmn" >/dev/null 2>&1; then
  log "Verificatum VMN ya instalado."
else
  log "Instalando Verificatum VMN 3.1.0..."
  multipass exec "$VM_NAME" -- bash -c "
    cd ~
    wget -q https://github.com/rmartinezch/mixnet/raw/main/installer/verificatum-vmn-3.1.0-full.tar.gz
    mkdir -p verificatum-vmn-3.1.0-full
    tar xfz verificatum-vmn-3.1.0-full.tar.gz -C verificatum-vmn-3.1.0-full
    cd verificatum-vmn-3.1.0-full
    sudo make install
  "
fi

# ── Validar instalacion ─────────────────────────────────────────────
log "Validando instalacion..."
multipass exec "$VM_NAME" -- bash -lc "
  vmn -version
  command -v vmn vmni vmnc vmnd vog vmnv
  python3 --version
  java -version 2>&1 | head -1
"

# ── Obtener IP de la VM ─────────────────────────────────────────────
VM_IP="$(multipass info "$VM_NAME" --format csv | tail -1 | cut -d, -f3)"

log ""
log "════════════════════════════════════════════════════════════════"
log "  VM '$VM_NAME' lista."
log ""
log "  IP:        $VM_IP"
log "  SSH:       multipass shell $VM_NAME"
log ""
log "  Para iniciar la mezcladora:"
log ""
log "    multipass exec $VM_NAME -- bash -lc \\"
log "      'cd ~/cifradorM/workflow/votante/mezcladora && ./start_gui.sh'"
log ""
log "  La GUI quedara en: http://$VM_IP:7040"
log "════════════════════════════════════════════════════════════════"
