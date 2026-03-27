#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUI_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${GUI_DIR}/dist"
BUILD_BASE="${DIST_DIR}/.build"

if command -v git >/dev/null 2>&1 && git -C "${GUI_DIR}/.." rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  GIT_HASH="$(git -C "${GUI_DIR}/.." rev-parse --short HEAD)"
else
  GIT_HASH="nogit"
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
PACKAGE_NAME="verificatum-gui-mezcladora-portable-${STAMP}-${GIT_HASH}"
PACKAGE_ROOT="${BUILD_BASE}/${PACKAGE_NAME}"
TARBALL_PATH="${DIST_DIR}/${PACKAGE_NAME}.tar.gz"

rm -rf "${PACKAGE_ROOT}"
mkdir -p "${PACKAGE_ROOT}/static" "${PACKAGE_ROOT}/runtime" "${DIST_DIR}" "${BUILD_BASE}"

cp "${GUI_DIR}/server.py" "${PACKAGE_ROOT}/server.py"
cp "${GUI_DIR}/start_gui.sh" "${PACKAGE_ROOT}/start_gui.sh"
cp "${GUI_DIR}/README.md" "${PACKAGE_ROOT}/README.md"
cp "${GUI_DIR}"/static/* "${PACKAGE_ROOT}/static/"

cat > "${PACKAGE_ROOT}/run_mixserver.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${HOST:-0.0.0.0}"
detect_public_host() {
  local candidate
  candidate="$(hostname -I 2>/dev/null | awk '{print $1}')"
  if [[ -n "${candidate}" ]]; then
    printf '%s\n' "${candidate}"
  else
    hostname
  fi
}
PUBLIC_HOST="${PUBLIC_HOST:-$(detect_public_host)}"
BASE_PORT="${1:-7040}"

for cmd in python3 vmn vmni vmnc vmnd vog; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Falta dependencia requerida: ${cmd}" >&2
    exit 1
  fi
done

export VERIFICATUM_GUI_RUNTIME_DIR="${VERIFICATUM_GUI_RUNTIME_DIR:-${SCRIPT_DIR}/runtime}"
export VERIFICATUM_GUI_CLEAN_START="${VERIFICATUM_GUI_CLEAN_START:-0}"

cd "${SCRIPT_DIR}"
echo "Iniciando mezcladora portable en http://${PUBLIC_HOST}:${BASE_PORT}"
echo "Runtime local: ${VERIFICATUM_GUI_RUNTIME_DIR}"
exec bash "${SCRIPT_DIR}/start_gui.sh" "${BASE_PORT}"
EOF

cat > "${PACKAGE_ROOT}/check_prereqs.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

MISSING=0
for cmd in python3 vmn vmni vmnc vmnd vog; do
  if command -v "${cmd}" >/dev/null 2>&1; then
    printf '[ok] %s -> %s\n' "${cmd}" "$(command -v "${cmd}")"
  else
    printf '[falta] %s\n' "${cmd}" >&2
    MISSING=1
  fi
done

exit "${MISSING}"
EOF

cat > "${PACKAGE_ROOT}/README-PORTABLE.md" <<EOF
# Mezcladora Verificatum Portable

Este paquete contiene la GUI web de la mezcladora lista para ejecutarse en cualquier Ubuntu donde Verificatum ya exista en el \`PATH\`.

## Contenido

- \`server.py\`
- \`start_gui.sh\`
- \`run_mixserver.sh\`
- \`check_prereqs.sh\`
- \`static/\`
- \`README.md\`

## Requisitos

- Ubuntu con \`python3\`
- Verificatum instalado y accesible en PATH:
  - \`vmn\`
  - \`vmni\`
  - \`vmnc\`
  - \`vmnd\`
  - \`vog\`

## Uso rapido

1. Extraer:

\`\`\`bash
tar -xzf ${PACKAGE_NAME}.tar.gz
cd ${PACKAGE_NAME}
\`\`\`

2. Verificar dependencias:

\`\`\`bash
./check_prereqs.sh
\`\`\`

3. Ejecutar:

\`\`\`bash
./run_mixserver.sh
\`\`\`

Opcionalmente:

\`\`\`bash
PUBLIC_HOST=\$(hostname -I | awk '{print \$1}') ./run_mixserver.sh 7040
\`\`\`

## Runtime

Por defecto el paquete guarda su estado en:

\`\`\`text
\${PAQUETE}/runtime
\`\`\`

Si quieres usar otra ruta:

\`\`\`bash
VERIFICATUM_GUI_RUNTIME_DIR=/ruta/runtime ./run_mixserver.sh
\`\`\`

## Limpieza de sesiones

El lanzador portable no limpia \`runtime/\` por defecto.

Para arrancar limpio:

\`\`\`bash
VERIFICATUM_GUI_CLEAN_START=1 ./run_mixserver.sh
\`\`\`
EOF

cat > "${PACKAGE_ROOT}/VERSION.txt" <<EOF
package_name=${PACKAGE_NAME}
generated_at=${STAMP}
git_hash=${GIT_HASH}
EOF

chmod +x "${PACKAGE_ROOT}/start_gui.sh" "${PACKAGE_ROOT}/run_mixserver.sh" "${PACKAGE_ROOT}/check_prereqs.sh"

tar -C "${BUILD_BASE}" -czf "${TARBALL_PATH}" "${PACKAGE_NAME}"

echo "Tarball generado:"
echo "${TARBALL_PATH}"
