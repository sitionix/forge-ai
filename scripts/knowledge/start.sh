#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
PYTHON="${KNOWLEDGE_ROOT}/.venv/bin/python3"
PID_FILE="${FORGE_RUNTIME_DIR}/knowledge/knowledge-service.pid"
STDOUT_LOG="${FORGE_RUNTIME_DIR}/knowledge/logs/knowledge-service.stdout.log"
HOST="${KNOWLEDGE_HOST:-127.0.0.1}"
PORT="${KNOWLEDGE_PORT:-7081}"

mkdir -p "${FORGE_RUNTIME_DIR}/knowledge/logs"

if [[ ! -x "${PYTHON}" ]] || ! PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" -c 'import uvicorn' >/dev/null 2>&1; then
  echo "Knowledge dependencies are missing. Run scripts/knowledge/bootstrap.sh first."
  exit 1
fi

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Knowledge service is already reachable at http://${HOST}:${PORT}/health"
elif [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1; then
  echo "Knowledge service is already running with PID $(cat "${PID_FILE}")"
else
  echo "Starting Knowledge service on ${HOST}:${PORT}..."
  (
    cd "${KNOWLEDGE_ROOT}"
    setsid nohup env \
      KNOWLEDGE_MODULE_DIR="${KNOWLEDGE_ROOT}" \
      FORGE_AI_HOME="${FORGE_AI_HOME}" \
      FORGE_CONFIG_DIR="${FORGE_CONFIG_DIR}" \
      FORGE_RUNTIME_DIR="${FORGE_RUNTIME_DIR}" \
      FORGE_WORKSPACE_ROOT="${FORGE_WORKSPACE_ROOT}" \
      KNOWLEDGE_HOST="${HOST}" \
      KNOWLEDGE_PORT="${PORT}" \
      PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" \
      "${PYTHON}" -m uvicorn knowledge_service.main:app \
      --app-dir "${KNOWLEDGE_ROOT}/src" \
      --host "${HOST}" \
      --port "${PORT}" \
      > "${STDOUT_LOG}" 2>&1 &
    echo $! > "${PID_FILE}"
  )
fi

echo "Knowledge health: http://${HOST}:${PORT}/health"
