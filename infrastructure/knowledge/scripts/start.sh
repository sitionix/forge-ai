#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SERVICE_DIR="${MODULE_DIR}/services/knowledge-service"
PID_FILE="${MODULE_DIR}/var/knowledge-service.pid"
STDOUT_LOG="${MODULE_DIR}/var/logs/knowledge-service.stdout.log"
HOST="${KNOWLEDGE_HOST:-127.0.0.1}"
PORT="${KNOWLEDGE_PORT:-7081}"

mkdir -p "${MODULE_DIR}/var/logs"

if [[ ! -x "${SERVICE_DIR}/.venv/bin/uvicorn" ]]; then
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
    cd "${MODULE_DIR}"
    setsid nohup env \
      KNOWLEDGE_MODULE_DIR="${MODULE_DIR}" \
      KNOWLEDGE_HOST="${HOST}" \
      KNOWLEDGE_PORT="${PORT}" \
      "${SERVICE_DIR}/.venv/bin/uvicorn" knowledge_service.main:app \
      --app-dir "${SERVICE_DIR}/src" \
      --host "${HOST}" \
      --port "${PORT}" \
      > "${STDOUT_LOG}" 2>&1 &
    echo $! > "${PID_FILE}"
  )
fi

echo "Knowledge health: http://${HOST}:${PORT}/health"
