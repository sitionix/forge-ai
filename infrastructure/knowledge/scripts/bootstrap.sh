#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SERVICE_DIR="${MODULE_DIR}/services/knowledge-service"

python3 -m venv "${SERVICE_DIR}/.venv"
"${SERVICE_DIR}/.venv/bin/pip" install --upgrade pip
"${SERVICE_DIR}/.venv/bin/pip" install -e "${SERVICE_DIR}[test]"

mkdir -p "${MODULE_DIR}/var/logs"

echo "Knowledge bootstrap complete."
echo "Next: scripts/knowledge/init-local-config.sh, then scripts/knowledge/start.sh"
