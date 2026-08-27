#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"
forge_systemd_load_env "${1:-}"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
PYTHON="${KNOWLEDGE_ROOT}/.venv/bin/python3"

exec env \
  KNOWLEDGE_MODULE_DIR="${KNOWLEDGE_ROOT}" \
  PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" \
  "${PYTHON}" -m uvicorn knowledge_service.main:app \
  --app-dir "${KNOWLEDGE_ROOT}/src" \
  --host "${KNOWLEDGE_HOST:-127.0.0.1}" \
  --port "${KNOWLEDGE_PORT:-7081}"
