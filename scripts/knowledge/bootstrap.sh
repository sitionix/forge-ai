#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"

python3 -m venv "${KNOWLEDGE_ROOT}/.venv"
"${KNOWLEDGE_ROOT}/.venv/bin/python3" -m pip install --upgrade pip
"${KNOWLEDGE_ROOT}/.venv/bin/python3" -m pip install -e "${KNOWLEDGE_ROOT}[test]"

mkdir -p "${FORGE_RUNTIME_DIR}/knowledge/logs"

echo "Knowledge bootstrap complete."
echo "Next: scripts/knowledge/init-local-config.sh, then 'just start'"
