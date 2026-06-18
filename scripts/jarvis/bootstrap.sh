#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"

python3 -m venv "${JARVIS_ROOT}/.venv"
"${JARVIS_ROOT}/.venv/bin/python3" -m pip install --upgrade pip
"${JARVIS_ROOT}/.venv/bin/python3" -m pip install -e "${JARVIS_ROOT}[test]"

mkdir -p "${FORGE_RUNTIME_DIR}/jarvis/logs" "${FORGE_RUNTIME_DIR}/jarvis/data/ollama"

echo "Jarvis bootstrap complete."
