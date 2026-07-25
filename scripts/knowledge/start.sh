#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
PYTHON="${KNOWLEDGE_ROOT}/.venv/bin/python3"
PID_FILE="${FORGE_RUNTIME_DIR}/knowledge/knowledge-service.pid"
STDOUT_LOG="${FORGE_RUNTIME_DIR}/knowledge/logs/knowledge-service.stdout.log"

mkdir -p "${FORGE_RUNTIME_DIR}/knowledge/logs"

if [[ ! -x "${PYTHON}" ]] || ! PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" -c 'import uvicorn' >/dev/null 2>&1; then
  echo "Knowledge dependencies are missing. Run scripts/knowledge/bootstrap.sh first."
  exit 1
fi

read -r CONFIG_HOST CONFIG_PORT < <(
  PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" - <<'PY'
from knowledge_service.config import load_forge_settings

settings = load_forge_settings()
knowledge = settings.services.knowledge
print(knowledge.host, knowledge.port)
PY
)
HOST="${KNOWLEDGE_HOST:-${CONFIG_HOST}}"
PORT="${KNOWLEDGE_PORT:-${CONFIG_PORT}}"

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Knowledge service is already reachable at http://${HOST}:${PORT}/health"
  exit 0
fi

if [[ -f "${PID_FILE}" ]]; then
  existing_pid="$(cat "${PID_FILE}")"
  existing_cmd="$(ps -p "${existing_pid}" -o command= 2>/dev/null || true)"
  if [[ -n "${existing_cmd}" && "${existing_cmd}" == *"knowledge_service.main:app"* ]]; then
    echo "Knowledge PID ${existing_pid} exists but health check failed; stopping unhealthy service."
    kill "${existing_pid}" >/dev/null 2>&1 || true
    sleep 1
  else
    echo "Ignoring stale Knowledge PID file: ${existing_pid}"
  fi
  rm -f "${PID_FILE}"
fi

if command -v lsof >/dev/null 2>&1; then
  port_pids="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "${port_pids}" ]]; then
    echo "Knowledge port ${PORT} is occupied by pid(s): ${port_pids}"
    exit 1
  fi
fi

echo "Starting Knowledge service on ${HOST}:${PORT}..."
(
  cd "${KNOWLEDGE_ROOT}"
  setsid nohup env \
    KNOWLEDGE_MODULE_DIR="${KNOWLEDGE_ROOT}" \
    FORGE_AI_HOME="${FORGE_AI_HOME}" \
    FORGE_CONFIG_DIR="${FORGE_CONFIG_DIR}" \
    FORGE_KNOWLEDGE_HUMAN_ANSWER_AUDIT_DIR="${FORGE_KNOWLEDGE_HUMAN_ANSWER_AUDIT_DIR:-}" \
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

for _ in {1..30}; do
  if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
    echo "Knowledge health: http://${HOST}:${PORT}/health"
    exit 0
  fi
  sleep 1
done

echo "Knowledge service did not become healthy at http://${HOST}:${PORT}/health"
echo "Last Knowledge log lines:"
tail -n 80 "${STDOUT_LOG}" || true
exit 1
