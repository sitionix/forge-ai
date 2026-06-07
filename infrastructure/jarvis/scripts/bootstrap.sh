#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
AGENT_DIR="${MODULE_DIR}/services/jarvis-agent"

python3 -m venv "${AGENT_DIR}/.venv"
"${AGENT_DIR}/.venv/bin/pip" install --upgrade pip
"${AGENT_DIR}/.venv/bin/pip" install -e "${AGENT_DIR}[test]"

mkdir -p "${MODULE_DIR}/var/logs" "${MODULE_DIR}/var/data/ollama"

echo "Jarvis bootstrap complete."
