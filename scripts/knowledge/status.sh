#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/forge-env.sh
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
PYTHON="${KNOWLEDGE_ROOT}/.venv/bin/python3"
if [[ -x "${PYTHON}" ]]; then
  read -r CONFIG_HOST CONFIG_PORT < <(
    PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" - <<'PY'
from knowledge_service.config import load_forge_settings

settings = load_forge_settings()
knowledge = settings.services.knowledge
print(knowledge.host, knowledge.port)
PY
  )
else
  CONFIG_HOST="127.0.0.1"
  CONFIG_PORT="7081"
fi
HOST="${KNOWLEDGE_HOST:-${CONFIG_HOST}}"
PORT="${KNOWLEDGE_PORT:-${CONFIG_PORT}}"
PID_FILE="${FORGE_RUNTIME_DIR}/knowledge/knowledge-service.pid"
PID="-"
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ -z "${PID}" ]] || ! kill -0 "${PID}" >/dev/null 2>&1; then
    PID="-"
  fi
fi
LISTENER="-"
if command -v lsof >/dev/null 2>&1; then
  LISTENER="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  LISTENER="${LISTENER:-"-"}"
fi

if curl --max-time 10 -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Knowledge service: UP at http://${HOST}:${PORT} pid=${PID} listener=${LISTENER}"
else
  echo "Knowledge service: DOWN at http://${HOST}:${PORT} pid=${PID} listener=${LISTENER}"
fi
